package io.nekohasekai.pm

import cn.hutool.core.date.DateUtil
import cn.hutool.core.date.SystemClock
import cn.hutool.core.io.FileUtil
import io.nekohasekai.ktlib.compress.*
import io.nekohasekai.ktlib.core.getValue
import io.nekohasekai.ktlib.core.input
import io.nekohasekai.ktlib.db.IdTableCacheMap
import io.nekohasekai.ktlib.db.forceCreateTables
import io.nekohasekai.ktlib.td.cli.TdCli
import io.nekohasekai.ktlib.td.core.persists.store.DatabasePersistStore
import io.nekohasekai.ktlib.td.core.raw.getChatWith
import io.nekohasekai.ktlib.td.extensions.*
import io.nekohasekai.ktlib.td.i18n.*
import io.nekohasekai.ktlib.td.i18n.store.*
import io.nekohasekai.ktlib.td.utils.*
import io.nekohasekai.ktlib.td.utils.commands.GetIdCommand
import io.nekohasekai.pm.database.*
import io.nekohasekai.pm.instance.*
import io.nekohasekai.pm.manage.*
import kotlinx.coroutines.*
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import td.TdApi
import java.io.File
import java.util.*
import kotlin.concurrent.schedule
import kotlin.system.exitProcess

open class TdPmBot(tag: String = "main", name: String = "TdPmBot") : TdCli(tag, name), PmInstance {

    companion object : TdPmBot() {

        const val repoName = "TdPmBot"
        const val repoUrl = "https://github.com/TdBotProject/TdPmBot"
        const val licenseUrl = "https://github.com/TdBotProject/TdPmBot/blob/master/LICENSE"

        @JvmStatic
        fun main(args: Array<String>) {

            launch(args)

            loadConfig()

            start()

        }

    }

    var public = false
    lateinit var whiteList: IntArray

    fun userAccessible(userId: Int): Boolean {

        if (userId == admin.toInt()) return true

        if (!public) return false

        if (!::whiteList.isInitialized) return true

        return whiteList.contains(userId)

    }

    @Suppress("ObjectPropertyName")
    private var _admin = 0L
    override val admin by ::_admin

    lateinit var users: Array<Long>

    val botIntegrations by lazy { IdTableCacheMap(database, BotIntegration) }
    val botSettings by lazy { IdTableCacheMap(database, BotSetting) }
    val actionMessages by lazy { IdTableCacheMap(database, ActionMessage) }
    val startMessages by lazy { StartMessages.Cache(database) }
    val botCommands by lazy { BotCommands.Cache(database) }

    override val integration get() = botIntegrations.fetch(me.id).value
    override val settings get() = botSettings.fetch(me.id).value
    override val blocks by lazy { UserBlocks.Cache(database, me.id) }

    val instanceMap = HashMap<Int, PmBot>()

    fun initBot(userBot: UserBot): PmBot {

        return instanceMap[userBot.botId] ?: synchronized(instanceMap) {

            PmBot(userBot.botToken, userBot, this).apply {

                instanceMap[botUserId] = this

                start()

            }

        }

    }

    override var configFile = File("pm.yml")

    override fun onLoadConfig() {

        super.onLoadConfig()

        _admin = intConfig("B0T_OWNER")?.toLong() ?: _admin

        if (admin == 0L) {

            clientLog.warn("Bot owner not specified, send /id to bot to get your userId.")

        }

        when (val pmMode = stringConfig("PM_MODE")?.toLowerCase() ?: "public") {

            "public" -> {

                public = true

            }

            "white-list" -> {

                public = true

                val accessibleUsers = listConfig("PM_WHITE_LIST")

                whiteList = accessibleUsers?.mapNotNull { item ->

                    runCatching { item.toInt() }.onFailure {

                        clientLog.warn(">> Invalid white-list user-id item: $item", it)

                    }.getOrNull()

                }?.toIntArray() ?: intArrayOf()

            }

            "private" -> {

                public = false

            }

            else -> {

                clientLog.error(">> Invalid mode defined: $pmMode")

                exitProcess(100)

            }

        }

    }

    override fun onArgument(argument: String, value: String?) {

        if (argument == "backup") {

            var backupTo: File

            backupTo = File(value?.replace("\$id", tag
                    .replace("  ", " ")
                    .replace(" ", "-")
                    .replace("_", "-")) ?: ".")

            if (backupTo.isDirectory) {

                @Suppress("SpellCheckingInspection")
                backupTo = File(backupTo, "td-pm-${
                    tag
                            .replace("  ", " ")
                            .replace(" ", "-")
                            .replace("_", "-")
                }-backup-${DateUtil.format(Date(), "yyyyMMdd-HHmmss")}.tar.xz")

            } else if (!backupTo.name.endsWith(".tar.xz")) {

                clientLog.error(">> File name must ends with .tar.xz")

                exitProcess(100)

            }

            backupTo = backupTo.canonicalFile

            val output = FileUtil.touch(backupTo).tarXz()

            output.writeFile("pm.yml", configFile)

            // 数据目录

            output.writeDirectory("data/")

            // 数据库

            val originDatabaseDirectory = File(options.databaseDirectory)

            output.writeFile("data/pm_data.db", File(originDatabaseDirectory, "pm_data.db"))

            output.writeFile("data/td.binlog", File(originDatabaseDirectory, "td.binlog"))

            val pmBots = File(originDatabaseDirectory, "pm").listFiles()

            if (!pmBots.isNullOrEmpty()) {

                output.writeDirectory("data/pm/")

                pmBots.forEach {

                    output.writeDirectory("data/pm/${it.name}/")

                    output.writeFile("data/pm/${it.name}/td.binlog", File(it, "td.binlog"))

                }

            }

            output.finish()

            output.close()

            clientLog.info(">> Saved to ${backupTo.path}")

            exitProcess(0)

        } else super.onArgument(argument, value)

    }

    override fun onLoad() {

        super.onLoad()

        clientLog.debug("Init databases")

        initDatabase("pm_data.db")

        if (LocaleStore.store is InMemoryLocaleStore) {

            LocaleStore.setImplement(DatabaseLocaleStore(database))

        }

        persists.setImplement(DatabasePersistStore(database))

        database.write {

            forceCreateTables(
                    UserBots,
                    StartMessages,
                    ActionMessages,
                    BotIntegrations,
                    BotSettings,
                    BotCommands,
                    MessageRecords,
                    UserBlocks
            )

        }

        if (public) initFunction("help")

        addHandler(LocaleSwitcher(DATA_SWITCH_LOCALE) { userId, chatId, message ->

            onLaunch(userId, chatId, message)

        })

        addHandler(CreateBot())
        addHandler(MyBots())

        addHandler(InputHandler(this))
        addHandler(OutputHandler(this))
        addHandler(EditHandler(this))
        addHandler(DeleteHandler(this))
        addHandler(JoinHandler(this))
        addHandler(RecallHandler(this))
        addHandler(UpgradeHandler(this))

        addHandler(GetIdCommand())
        addHandler(AdminCommands())

    }

    suspend fun updateCommands() {

        val commands = database {
            BotCommands
                    .select { commandsForCurrentBot and (BotCommands.hide eq false) }
                    .map { TdApi.BotCommand(it[BotCommands.command], it[BotCommands.description]) }
                    .toTypedArray()
        }

        if (public) {

            upsertCommands(
                    findHandler<CreateBot>().def(),
                    findHandler<MyBots>().def(),
                    findHandler<LocaleSwitcher>().def(),
                    * commands,
                    HELP_COMMAND,
                    CANCEL_COMMAND
            )

        } else {

            upsertCommands(* commands)

        }

    }

    override suspend fun skipFloodCheck(senderUserId: Int, message: TdApi.Message) = senderUserId == admin.toInt()

    override suspend fun onLogin() {

        updateCommands()

        database {

            clientLog.trace("Loading PM Bots")

            UserBot.all().forEach { initBot(it) }

        }

        timer.schedule(Date(nextDay()), 24 * 60 * 60 * 1000L) {

            GlobalScope.launch(Dispatchers.IO) { gc() }

        }

    }

    override suspend fun gc() {

        clientLog.debug(">> 执行垃圾回收")

        clientLog.debug(">> 内存缓存")

        super.gc()

        botIntegrations.gc()
        botSettings.gc()
        actionMessages.gc()
        startMessages.gc()
        botCommands.gc()

        clientLog.debug(">> 清理数据库")

        val time = (SystemClock.now() / 1000L).toInt() - 24 * 60 * 60

        database.write {

            val result = ActionMessage.find { ActionMessages.createAt less time }

            result.forEach { row ->

                val chatId = row.userId.toLong()

                getChatWith(chatId) {

                    onSuccess {

                        delete(chatId, row.messageId)

                    }

                }

            }

            ActionMessages.deleteWhere { ActionMessages.createAt less time }

            @Suppress("UNCHECKED_CAST")
            fun deleteUnusedBotData(
                    vararg tables: Table
            ) {

                for (table in tables) {

                    val idColumn: Column<Int> = (table.indices
                            .find { it.columns.size == 1 && it.columns[0].name == "bot_id" }
                            ?.columns?.get(0)
                            ?: ((table as? IdTable<Int>)!!.id.columnType as EntityIDColumnType<*>).idColumn) as Column<Int>

                    table.deleteWhere { (idColumn neq me.id) and (idColumn notInSubQuery UserBots.selectAll().adjustSlice { slice(UserBots.botId) }) }

                }

            }

            deleteUnusedBotData(
                    BotCommands,
                    BotIntegrations,
                    BotSettings,
                    MessageRecords,
                    StartMessages,
                    UserBlocks
            )

        }

        clientLog.trace(">> ${me.displayNameFormatted}")

        gc(this)

        instanceMap.forEach {

            clientLog.trace(">> ${it.value.me.displayNameFormatted}")

            it.value.gc()

        }

        clientLog.debug("<< 执行垃圾回收")

    }

    override suspend fun userBlocked(userId: Int) = blocks.fetch(userId).value == true

    override suspend fun onUndefinedFunction(userId: Int, chatId: Long, message: TdApi.Message, function: String, param: String, params: Array<String>, originParams: Array<String>) {

        if (function == "cancel") {

            if (!public) rejectFunction()

            super.onUndefinedFunction(userId, chatId, message, function, param, params, originParams)

            return

        }

        val command = botCommands.fetch(me.id to function).value?.takeIf { !it.hide }

        if (message.fromPrivate) {

            if (command == null) {

                onLaunch(userId, chatId, message)

                finishEvent()

            }

            command.messages.forEach { sudo make it syncTo chatId }

            if ((!public || command.inputWhenPublic) && chatId != admin) writePersist(userId, PERSIST_UNDER_FUNCTION, 0L, function, command.inputWhenPublic)

            return

        } else {

            if (command == null) finishEvent()

            command.messages.forEach { sudo make it syncTo chatId }


        }

    }

    override suspend fun onUndefinedPayload(userId: Int, chatId: Long, message: TdApi.Message, payload: String, params: Array<String>) {

        if (message.fromPrivate) {

            val command = botCommands.fetch(me.id to payload).value?.takeIf { !it.hide } ?: rejectFunction()

            command.messages.forEach { sudo make it syncTo chatId }

            if ((!public || command.inputWhenPublic) && chatId != admin) writePersist(userId, PERSIST_UNDER_FUNCTION, 0L, payload, command.inputWhenPublic)

        } else rejectFunction()

    }

    override suspend fun onLaunch(userId: Int, chatId: Long, message: TdApi.Message) {

        if (!message.fromPrivate) return

        val L = localeFor(userId)

        val startMessages = startMessages.fetch(me.id).value

        if (!public && chatId != admin) {

            if (startMessages == null) {

                sudo make L.DEFAULT_WELCOME sendTo chatId

            } else {

                startMessages.forEach {

                    sudo make it syncTo chatId

                }

            }

            return

        }

        if (LocaleStore.localeRead(chatId) == null) {

            findHandler<LocaleSwitcher>().startSelect(L, chatId, true)

            return

        }

        sudo makeHtml L.LICENSE.input(repoName, licenseUrl, "Github Repo".htmlLink(repoUrl)) syncTo chatId

        delay(600L)

        if (startMessages == null) {

            sudo make L.HELP_MSG sendTo chatId

        } else {

            startMessages.forEach {

                sudo make it syncTo chatId

            }

        }

    }

    override suspend fun onFunction(userId: Int, chatId: Long, message: TdApi.Message, function: String, param: String, params: Array<String>, originParams: Array<String>) {

        sudo make localeFor(chatId, userId).HELP_MSG sendTo chatId

    }

}