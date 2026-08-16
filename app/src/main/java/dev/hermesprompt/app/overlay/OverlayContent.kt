package dev.hermesprompt.app.overlay

/**
 * Contract between [OverlayService] (the WindowManager window host) and the
 * overlay UI view attached by the wiring layer.
 *
 * The service knows nothing about Compose: whoever attaches the UI builds a
 * [android.view.View] plus an adapter implementing this interface, and passes
 * both to [OverlayService.setOverlayContent]. The service then pushes the
 * initial question text and streamed answers into the UI, and the UI's own
 * dismiss callback calls [OverlayService.dismissOverlay] (or the
 * [OverlayService.Companion.dismiss] intent helper) when the user taps off.
 */
interface OverlayContent {

    /** Called when the overlay is (re)shown with the given initial question text. */
    fun setInitialText(text: String)

    /** Called with each streamed delta / final answer that should be rendered. */
    fun setAnswer(text: String)
}
