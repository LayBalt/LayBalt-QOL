package com.laybalt.init.types

import com.laybalt.commands.CommandBuilder

interface ICommandProvider {
    fun CommandBuilder.command()
}