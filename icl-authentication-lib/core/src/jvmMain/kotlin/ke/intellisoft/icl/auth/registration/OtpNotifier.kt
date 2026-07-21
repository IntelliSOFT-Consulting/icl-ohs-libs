package ke.intellisoft.icl.auth.registration

/** Delivers a registration OTP to [destination] (an email address or phone number). */
fun interface OtpNotifier {
    fun send(destination: String, otp: String)
}

/**
 * Default [OtpNotifier] - logs instead of sending, so registration is testable locally
 * without a real inbox/SMS provider. Swap in a real implementation via [ke.intellisoft.icl.auth.IclOHSAuth]'s
 * constructor once one is available.
 *
 * icl-notification-lib (a sibling project) is the natural place to source a real
 * implementation from, but it's a separate Gradle build with no Maven publishing
 * configured yet, so there's no dependency path to it today. Once it publishes `core` as
 * an artifact, a real notifier would look like:
 *
 * ```kotlin
 * class NotificationLibOtpNotifier(private val notifyService: NotifyService) : OtpNotifier {
 *     override fun send(destination: String, otp: String) {
 *         runBlocking { notifyService.sendEmail(EmailRequest(to = destination, template = "otp", vars = mapOf("code" to otp))) }
 *     }
 * }
 * ```
 */
object LoggingOtpNotifier : OtpNotifier {
    override fun send(destination: String, otp: String) {
        println("[IclOHSAuth] OTP for $destination: $otp (stub - no real delivery configured)")
    }
}
