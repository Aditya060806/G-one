package com.infinity.ai.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Corner radii, as a scale rather than a per-call-site guess.
 *
 * Mixed radii on adjacent surfaces is one of those flaws nobody can name but everybody
 * feels. Three steps is enough for this app.
 */
object GoneRadius {
    val Small = 10.dp
    val Pill = 14.dp
    val Card = 18.dp
    val Hero = 26.dp
}

/**
 * Elevation steps for the light theme.
 *
 * Dark themes separate surfaces with lightness and borders; light themes separate them
 * with shadow. Using borders on light produces the wireframe look the app had — every card
 * outlined in grey, nothing appearing to sit above anything else.
 */
object GoneElevation {
    val Flat = 0.dp
    val Card = 2.dp
    val Raised = 6.dp
    val Hero = 12.dp
}

/**
 * The app's standard surface treatment, in one place.
 *
 * THE ASYMMETRY IS THE POINT: on light it casts a soft blue-tinted shadow with only a
 * hairline border; on dark it draws no shadow at all and relies on a visible border. A
 * shadow over a near-black background is invisible work — it costs a render pass and
 * changes nothing — while a border on white is what makes a card look like a diagram
 * instead of an object.
 *
 * Every health surface routes through this so elevation cannot drift between screens.
 */
@Composable
fun goneSurface(
    darkTheme: Boolean,
    corner: Dp = GoneRadius.Card,
    elevation: Dp = GoneElevation.Card
): Modifier {
    return if (darkTheme) {
        Modifier
            .clip(RoundedCornerShape(corner))
            .background(DarkSurface)
            .border(1.dp, DarkBorder, RoundedCornerShape(corner))
    } else {
        Modifier
            // shadow() must precede clip/background, or it draws inside the clip and
            // disappears entirely.
            .shadow(
                elevation = elevation,
                shape = RoundedCornerShape(corner),
                ambientColor = LightShadow,
                spotColor = LightShadow
            )
            .clip(RoundedCornerShape(corner))
            .background(LightSurface)
            .border(1.dp, LightBorder, RoundedCornerShape(corner))
    }
}

/**
 * A themed card.
 *
 * [onClick] also wires press feedback through a shared interaction source, so a tappable
 * card visibly responds instead of only navigating.
 */
@Composable
fun GoneCard(
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    corner: Dp = GoneRadius.Card,
    elevation: Dp = GoneElevation.Card,
    contentPadding: Dp = 14.dp,
    verticalSpacing: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .then(if (onClick != null) Modifier.pressScale(interaction) else Modifier)
            .then(goneSurface(darkTheme, corner, elevation))
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                ) else Modifier
            )
            .padding(contentPadding),
        verticalArrangement = if (verticalSpacing > 0.dp) {
            Arrangement.spacedBy(verticalSpacing)
        } else {
            Arrangement.Top
        },
        content = content
    )
}
