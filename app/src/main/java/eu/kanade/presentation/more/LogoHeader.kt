package eu.kanade.presentation.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R

@Composable
fun LogoHeader(
    iconPadding: PaddingValues = PaddingValues(),
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .padding(iconPadding)
                .size(76.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                .background(androidx.compose.ui.graphics.Color(0xFF1F4D3A)),
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.ic_grove_fg),
                contentDescription = null,
                modifier = Modifier.size(76.dp),
            )
        }
        androidx.compose.material3.Text(
            text = "Grovebook",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 20.dp),
        )

        HorizontalDivider()
    }
}
