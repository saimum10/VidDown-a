package com.viddown.engine.newpipe.adapter

import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.EngineId
import com.viddown.engine.newpipe.StreamTransfer
import java.io.IOException
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.FoundAdException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Translates the extractor's exceptions into VidDown's error model. Access restrictions are reported as what they are and are never
 * worked around: no sign-in, no age-gate tricks, no region tricks, no captcha solving, no DRM handling.
 */
internal object ExtractionErrors {
    private val id = EngineId.NEWPIPE

    fun translate(e: Throwable): DownloadError {
        val chain = generateSequence(e) { it.cause }.take(8).toList()
        // A restriction or a network problem anywhere in the cause chain explains more than the generic wrapper around it.
        for (t in chain) specific(t)?.let { return it }
        return generic(e)
    }

    private fun detail(t: Throwable) = "${t.javaClass.simpleName}: ${t.message ?: "no message"}"

    private fun specific(t: Throwable): DownloadError? = when (t) {
        is AgeRestrictedContentException -> DownloadError.accessRestricted(id, "This video is age-restricted.", detail(t))
        is PrivateContentException -> DownloadError.accessRestricted(id, "This content is private.", detail(t))
        is PaidContentException, is YoutubeMusicPremiumContentException, is SoundCloudGoPlusContentException ->
            DownloadError.accessRestricted(id, "This content needs a paid subscription or a purchase.", detail(t))
        is GeographicRestrictionException, is UnsupportedContentInCountryException ->
            DownloadError.accessRestricted(id, "This content is not available in your region.", detail(t))
        is SignInConfirmNotBotException ->
            DownloadError.accessRestricted(id, "The site wants you to sign in or confirm that you are not a bot.", detail(t))
        is ReCaptchaException ->
            DownloadError.accessRestricted(id, "The site asks for a captcha check or is limiting requests.", detail(t))
        is AccountTerminatedException -> DownloadError.resourceUnavailable(detail(t))
        is FoundAdException -> DownloadError.unsupportedResource(id, "The address points to an advertisement")
        is ContentNotSupportedException -> DownloadError.unsupportedResource(id, detail(t))
        is IOException -> StreamTransfer.errorFromIo(t)
        else -> null
    }

    private fun generic(e: Throwable): DownloadError = when {
        e is ContentNotAvailableException -> DownloadError.resourceUnavailable(detail(e))
        e is StreamInfo.StreamExtractException ->
            DownloadError.noUsableFormat(id, "The site gave no streams for it.", detail(e))
        e is ParsingException ->
            // The page was read but not understood: the site changed. Only a newer extractor fixes that.
            DownloadError.extractionFailed(id, "${detail(e)} (the site may have changed; an updated extractor may be needed)")
        e is ExtractionException && e.message?.contains("No service can handle", ignoreCase = true) == true ->
            DownloadError.unsupportedResource(id, detail(e))
        else -> DownloadError.extractionFailed(id, detail(e))
    }
}
