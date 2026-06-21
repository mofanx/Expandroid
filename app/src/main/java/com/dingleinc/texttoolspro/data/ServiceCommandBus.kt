package com.dingleinc.texttoolspro.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ServiceCommandBus {
    sealed class Command {
        data class Add(val match: Match) : Command()
        data class Remove(val match: Match) : Command()
        data object Quit : Command()
        data object Reset : Command()
        data class UpdateGlobals(val globals: List<Var>) : Command()
    }

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 64)
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    suspend fun send(command: Command) {
        _commands.emit(command)
    }

    fun trySend(command: Command) {
        _commands.tryEmit(command)
    }
}
