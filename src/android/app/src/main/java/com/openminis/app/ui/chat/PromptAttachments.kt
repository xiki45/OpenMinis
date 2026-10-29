package com.openminis.app.ui.chat

/**
 * [T-headless-own-attachments] Which files a prompt sends, and whether it
 * empties the composer.
 *
 * The composer's staged files belong to the user's own send. A prompt nobody
 * typed - a scheduled fire, a CLI / RPC prompt, a sub agent's result or
 * progress report, a steer nudge, the answer to a deferred question - sends
 * only the files it brought and leaves the composer alone, as iOS leaves the
 * draft text alone (2a06de66a). iOS still shares its `attachments` staging
 * area with programmatic prompts on purpose, because its CLI and Shortcuts
 * stage files there first; Android's headless callers pass their files as
 * an argument instead, so nothing needs the shared area.
 */
internal object PromptAttachments {
    fun <T> of(ownsComposer: Boolean, own: List<T>, composer: List<T>): List<T> = if (ownsComposer) composer else own

    fun clearsComposer(ownsComposer: Boolean): Boolean = ownsComposer
}
