package com.dingleinc.texttoolspro.extension.shell;

interface IShellService {
    String executeCommand(in String[] command, in String[] envKeys, in String[] envValues, long timeoutMs);
}
