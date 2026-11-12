package com.offlinemaps.app.install

import kotlinx.coroutines.CancellationException
import java.io.EOFException
import java.util.zip.DataFormatException
import java.util.zip.ZipException

/**
 * Turns anything thrown while preparing a package into something a user can act on.
 *
 * Kept out of [PackageInstaller] because that class needs a Context and a ServiceLocator, while the
 * sentences below are the only part of a failed install a user ever sees - so they are pinned by a
 * plain JVM test, one case per branch.
 */
internal object InstallFailures {

    /**
     * True when the throwable means "this work was cancelled", not "this work failed".
     *
     * A cancelled install has nothing to report: the ViewModel that started it is going away, and a
     * failure message would claim something went wrong when nothing did.
     */
    fun isCancellation(error: Throwable): Boolean = error is CancellationException

    /** The sentence shown for [error] while installing [subject]; never leaks a stack trace. */
    fun describe(error: Throwable, subject: String): String = when (error) {
        is NoClassDefFoundError, is NoSuchMethodError ->
            "The routing engine needs a component that is missing on this device " +
                "(${error.message}). This is a packaging problem in the app, not something you did - " +
                "please report it."
        is OutOfMemoryError ->
            "Not enough memory to prepare $subject. Close other apps and try again."
        is SecurityException ->
            "Storage permission was refused, so $subject could not be installed."
        // A truncated gzip, a bad zip entry or a broken deflate stream all mean the same thing to
        // the user, and their own messages ("invalid entry CRC ...", "Unexpected end of ZLIB input
        // stream") say nothing anyone can act on.
        is ZipException, is DataFormatException, is EOFException ->
            "The downloaded file for $subject is damaged or incomplete, so it could not be opened. " +
                "Delete it and download it again."
        else -> error.message ?: "Installing $subject failed (${error.javaClass.simpleName})."
    }
}
