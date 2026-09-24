package com.eter.rutacree

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Help
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
@Preview
fun App() {
    val bgPrimary = Color(0xFFe6ecf5)
    val accentMint = Color(0xFFA8E6CF)
    val accentBlue = Color(0xFF8ECAE6)
    val textPrimary = Color(0xFF2c3e50)
    val textSecondary = Color(0xFF5a6c7d)

    var currentScreen by remember { mutableStateOf(Screen.DASHBOARD) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bgPrimary
    ) {
        Box {
            AnimatedVisibility(
                visible = currentScreen == Screen.DASHBOARD,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                DashboardScreen(bgPrimary, accentMint, accentBlue, textPrimary, textSecondary)
            }

            AnimatedVisibility(
                visible = currentScreen == Screen.ASSISTANCE,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                AssistanceScreen(bgPrimary, accentMint, accentBlue, textPrimary, textSecondary)
            }

            AnimatedVisibility(
                visible = currentScreen == Screen.PROFILE,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ProfileScreen(bgPrimary, accentMint, accentBlue, textPrimary, textSecondary)
            }

            AnimatedVisibility(
                visible = currentScreen == Screen.REPORTS,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ReportsScreen(bgPrimary, accentMint, accentBlue, textPrimary, textSecondary)
            }

            BottomNavigation(
                currentScreen = currentScreen,
                onScreenChange = { currentScreen = it },
                bgPrimary = bgPrimary,
                accentBlue = accentBlue,
                textSecondary = textSecondary
            )
        }
    }
}

enum class Screen {
    DASHBOARD, ASSISTANCE, REPORTS, PROFILE
}

@Composable
fun DashboardScreen(
    bgPrimary: Color,
    accentMint: Color,
    accentBlue: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = accentBlue,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .marginBottom(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("RutaCree", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Person, contentDescription = "Perfil", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Text("Centro Histórico, Durango", fontSize = 13.sp, color = Color.White.copy(0.95f))
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .neomorphicInset(bgPrimary)
                .background(Color(0xFFd8e7f5)),
            contentAlignment = Alignment.Center
        ) {
            Text("Mapa en vivo", fontSize = 14.sp, color = textSecondary)
        }

        RouteCard(accentMint, accentBlue, textPrimary, textSecondary, bgPrimary)
    }
}

@Composable
fun RouteCard(
    accentMint: Color,
    accentBlue: Color,
    textPrimary: Color,
    textSecondary: Color,
    bgPrimary: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 20.dp)
            .neomorphicCard(bgPrimary)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.LocationOn,
                    contentDescription = "Autobús",
                    tint = accentBlue,
                    modifier = Modifier.size(36.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Ruta 2 - Blvd. Dolores del Río", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Unidad Adaptada", fontSize = 12.sp, color = Color(0xFF5a6c7d))
                }
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(accentMint.copy(0.2f)),
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.padding(6.dp, 6.dp, 12.dp, 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("●", color = accentMint, fontSize = 8.sp)
                        Text("Operativa", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accentMint)
                    }
                }
            }

            Divider(color = Color(0xFFC5CBD5).copy(0.3f), thickness = 1.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("3", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = accentMint)
                    Text("min", fontSize = 11.sp, color = Color(0xFF5a6c7d))
                }
                Text("1.2 km de distancia", fontSize = 13.sp, color = Color(0xFF5a6c7d))
            }
        }
    }
}

@Composable
fun AssistanceScreen(
    bgPrimary: Color,
    accentMint: Color,
    accentBlue: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    var isPressed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .neomorphicCard(bgPrimary)
                .padding(12.dp, 8.dp, 12.dp, 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Estás en: Parada 20 de Noviembre", fontSize = 14.sp, color = textPrimary)
        }

        AnimatedVisibility(
            visible = isPressed,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .neomorphicInset(bgPrimary)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Enviando señal al chofer...", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = accentMint)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Solicitar Asistencia", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = textPrimary)
            Text("Presiona el botón para pedir rampa", fontSize = 14.sp, color = textSecondary)

            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(CircleShape)
                    .then(
                        if (isPressed) {
                            Modifier.neomorphicInset(bgPrimary)
                        } else {
                            Modifier.neomorphicCard(bgPrimary)
                        }
                    )
                    .clickable { isPressed = !isPressed }
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = "Rampa",
                        tint = accentMint,
                        modifier = Modifier.size(64.dp)
                    )
                    Text("Solicitar\nRampa", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textPrimary, textAlign = TextAlign.Center)
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { isPressed = false },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .neomorphicCard(bgPrimary),
            colors = ButtonDefaults.buttonColors(containerColor = bgPrimary)
        ) {
            Text("Cancelar", color = textSecondary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ProfileScreen(
    bgPrimary: Color,
    accentMint: Color,
    accentBlue: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    var name by remember { mutableStateOf("Alfredo Valadez") }
    var stop by remember { mutableStateOf("Parada 20 de Noviembre") }
    val assistanceTypes = remember { mutableStateOf(mapOf(
        "wheelchair" to true,
        "vision" to false,
        "mobility" to false,
        "hearing" to false
    )) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 100.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Person, contentDescription = "Perfil", modifier = Modifier.size(32.dp))
                Text("Mi Perfil", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("DATOS BÁSICOS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = textSecondary, letterSpacing = 0.5.sp)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre completo") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .neomorphicInset(bgPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = bgPrimary,
                        focusedContainerColor = bgPrimary,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent
                    )
                )
                OutlinedTextField(
                    value = stop,
                    onValueChange = { stop = it },
                    label = { Text("Parada frecuente") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .neomorphicInset(bgPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = bgPrimary,
                        focusedContainerColor = bgPrimary,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent
                    )
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("TIPO DE ASISTENCIA", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = textSecondary, letterSpacing = 0.5.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AssistanceToggle(
                        label = "Silla de\nRuedas",
                        isActive = assistanceTypes.value["wheelchair"] == true,
                        onToggle = { assistanceTypes.value = assistanceTypes.value + ("wheelchair" to !assistanceTypes.value["wheelchair"]!!) },
                        bgPrimary = bgPrimary,
                        accentMint = accentMint,
                        modifier = Modifier.weight(1f)
                    )
                    AssistanceToggle(
                        label = "Visión",
                        isActive = assistanceTypes.value["vision"] == true,
                        onToggle = { assistanceTypes.value = assistanceTypes.value + ("vision" to !assistanceTypes.value["vision"]!!) },
                        bgPrimary = bgPrimary,
                        accentMint = accentMint,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AssistanceToggle(
                        label = "Movilidad",
                        isActive = assistanceTypes.value["mobility"] == true,
                        onToggle = { assistanceTypes.value = assistanceTypes.value + ("mobility" to !assistanceTypes.value["mobility"]!!) },
                        bgPrimary = bgPrimary,
                        accentMint = accentMint,
                        modifier = Modifier.weight(1f)
                    )
                    AssistanceToggle(
                        label = "Audición",
                        isActive = assistanceTypes.value["hearing"] == true,
                        onToggle = { assistanceTypes.value = assistanceTypes.value + ("hearing" to !assistanceTypes.value["hearing"]!!) },
                        bgPrimary = bgPrimary,
                        accentMint = accentMint,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            Button(
                onClick = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(top = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accentBlue)
            ) {
                Text("Guardar Cambios", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun AssistanceToggle(
    label: String,
    isActive: Boolean,
    onToggle: () -> Unit,
    bgPrimary: Color,
    accentMint: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .then(
                if (isActive) {
                    Modifier.neomorphicInset(bgPrimary)
                } else {
                    Modifier.neomorphicCard(bgPrimary)
                }
            )
            .clickable { onToggle() }
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isActive) accentMint else Color(0xFFd0d8e0)),
                contentAlignment = Alignment.Center
            ) {
                if (isActive) {
                    Text("●", color = accentMint, fontSize = 20.sp)
                }
            }
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun ReportsScreen(
    bgPrimary: Color,
    accentMint: Color,
    accentBlue: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    var selectedReport by remember { mutableStateOf<String?>(null) }
    var comment by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 100.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Assessment, contentDescription = "Reportes", modifier = Modifier.size(32.dp))
                Text("Reportar Problema", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .neomorphicCard(bgPrimary)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Ruta: 2 | Unidad: 245 | Ubicación: Centro", fontSize = 12.sp, color = textSecondary)
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("SELECCIONA EL PROBLEMA", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = textSecondary, letterSpacing = 0.5.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ReportButton("Rampa\nDescompuesta", selectedReport == "ramp", { selectedReport = "ramp" }, bgPrimary, accentMint, modifier = Modifier.weight(1f))
                    ReportButton("El Chofer\nNo Paró", selectedReport == "driver", { selectedReport = "driver" }, bgPrimary, accentMint, modifier = Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ReportButton("Cupo\nLleno", selectedReport == "full", { selectedReport = "full" }, bgPrimary, accentMint, modifier = Modifier.weight(1f))
                    ReportButton("Parada\nInaccesible", selectedReport == "access", { selectedReport = "access" }, bgPrimary, accentMint, modifier = Modifier.weight(1f))
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DETALLES ADICIONALES", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = textSecondary, letterSpacing = 0.5.sp)
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    placeholder = { Text("Cuéntanos más detalles...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .neomorphicInset(bgPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = bgPrimary,
                        focusedContainerColor = bgPrimary,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent
                    )
                )
            }
        }

        item {
            Button(
                onClick = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accentBlue)
            ) {
                Text("Enviar Reporte", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ReportButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    bgPrimary: Color,
    accentMint: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .then(
                if (isSelected) {
                    Modifier.neomorphicInset(bgPrimary)
                } else {
                    Modifier.neomorphicCard(bgPrimary)
                }
            )
            .clickable { onClick() }
            .padding(12.dp)
            .heightIn(min = 80.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
fun BottomNavigation(
    currentScreen: Screen,
    onScreenChange: (Screen) -> Unit,
    bgPrimary: Color,
    accentBlue: Color,
    textSecondary: Color
) {
    NavigationBar(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        containerColor = bgPrimary,
        windowInsets = WindowInsets.navigationBars
    ) {
        NavigationBarItem(
            selected = currentScreen == Screen.DASHBOARD,
            onClick = { onScreenChange(Screen.DASHBOARD) },
            icon = { Icon(Icons.Outlined.LocationOn, contentDescription = "Radar") },
            label = { Text("Radar", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = Color.Transparent,
                selectedIconColor = accentBlue,
                selectedTextColor = accentBlue,
                unselectedIconColor = textSecondary,
                unselectedTextColor = textSecondary
            )
        )

        NavigationBarItem(
            selected = currentScreen == Screen.ASSISTANCE,
            onClick = { onScreenChange(Screen.ASSISTANCE) },
            icon = { Icon(Icons.Outlined.Help, contentDescription = "Ayuda") },
            label = { Text("Ayuda", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = Color.Transparent,
                selectedIconColor = accentBlue,
                selectedTextColor = accentBlue,
                unselectedIconColor = textSecondary,
                unselectedTextColor = textSecondary
            )
        )

        NavigationBarItem(
            selected = currentScreen == Screen.REPORTS,
            onClick = { onScreenChange(Screen.REPORTS) },
            icon = { Icon(Icons.Outlined.Assessment, contentDescription = "Reportes") },
            label = { Text("Reportes", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = Color.Transparent,
                selectedIconColor = accentBlue,
                selectedTextColor = accentBlue,
                unselectedIconColor = textSecondary,
                unselectedTextColor = textSecondary
            )
        )

        NavigationBarItem(
            selected = currentScreen == Screen.PROFILE,
            onClick = { onScreenChange(Screen.PROFILE) },
            icon = { Icon(Icons.Default.Person, contentDescription = "Perfil") },
            label = { Text("Perfil", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = Color.Transparent,
                selectedIconColor = accentBlue,
                selectedTextColor = accentBlue,
                unselectedIconColor = textSecondary,
                unselectedTextColor = textSecondary
            )
        )
    }
}

fun Modifier.neomorphicCard(bgPrimary: Color) = this
    .shadow(8.dp, RoundedCornerShape(16.dp), ambientColor = Color(0xFFC5CBD5), spotColor = Color(0xFFC5CBD5))
    .shadow((-8).dp, RoundedCornerShape(16.dp), ambientColor = Color(0xFFFFFFFF), spotColor = Color(0xFFFFFFFF))
    .background(bgPrimary, RoundedCornerShape(16.dp))

fun Modifier.neomorphicInset(bgPrimary: Color) = this
    .clip(RoundedCornerShape(12.dp))
    .background(bgPrimary)
    .shadow(4.dp, RoundedCornerShape(12.dp), ambientColor = Color(0xFFC5CBD5), spotColor = Color(0xFFC5CBD5), clip = false)

fun Modifier.marginBottom(dp: Dp) = this.padding(bottom = dp)