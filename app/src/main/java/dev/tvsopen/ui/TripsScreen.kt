package dev.tvsopen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tvsopen.session.Ride
import dev.tvsopen.session.Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local ride history, kept separate from configuration and live riding controls. */
@Composable
fun TripsScreen(modifier: Modifier = Modifier) {
    val rides by Session.rides.collectAsStateWithLifecycle()
    val totalRides by Session.totalRides.collectAsStateWithLifecycle()
    val totalDistanceKm by Session.totalRideDistanceKm.collectAsStateWithLifecycle()
    val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy · HH:mm", Locale.getDefault())

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Trips", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Your ride history, stored locally on this phone",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCard(
                    modifier = Modifier.weight(1f),
                    value = totalRides.toString(),
                    label = "Recorded rides",
                )
                SummaryCard(
                    modifier = Modifier.weight(1f),
                    value = String.format(Locale.US, "%.1f", totalDistanceKm),
                    label = "Total km",
                )
            }
        }
        item {
            Text("Recent rides", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
        }
        if (rides.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No rides recorded yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Connect to your scooter and start riding. Completed rides will appear here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(rides, key = { it.startedAt }) { ride ->
                TripCard(ride = ride, dateLabel = dateFormat.format(Date(ride.startedAt)))
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Text(
                "Distance is calculated locally from the cluster odometer. No account or cloud history is used.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryCard(modifier: Modifier, value: String, label: String) {
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TripCard(ride: Ride, dateLabel: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(dateLabel, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold)
                if (ride.inProgress) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small) {
                        Text("In progress", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column {
                    Text(String.format(Locale.US, "%.1f km", ride.distanceKm),
                        style = MaterialTheme.typography.titleMedium)
                    Text("Distance", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column {
                    Text("${ride.durationMinutes()} min", style = MaterialTheme.typography.titleMedium)
                    Text("Duration", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
