package com.example

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.ChatMessage
import com.example.model.Peer
import com.example.security.EncryptionManager
import com.example.ui.theme.*
import com.example.viewmodel.MessengerUiState
import com.example.viewmodel.MessengerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SleekDark)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) { innerPadding ->
                    val viewModel: MessengerViewModel = viewModel()
                    MessengerDesktopWorkspace(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun MessengerDesktopWorkspace(
    viewModel: MessengerViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.activeChatMessages.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Trigger error alerts
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { error ->
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(SleekDark)
    ) {
        val isDesktopLayout = maxWidth > 680.dp

        if (isDesktopLayout) {
            // Adaptive Desktop-styled side-by-side split screen
            Row(modifier = Modifier.fillMaxSize()) {
                // Left Panel: Sidebar Contacts, Profile, and Scans (width: 340dp)
                Surface(
                    modifier = Modifier
                        .width(340.dp)
                        .fillMaxHeight()
                        .border(1.dp, SleekBorder),
                    color = SleekSurface
                ) {
                    SidebarControlPanel(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                }

                // Divider line
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(SleekBorder)
                )

                // Right Panel: Messages Workspace
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(SleekDark)
                ) {
                    ChatWorkspace(
                        uiState = uiState,
                        messages = messages,
                        viewModel = viewModel,
                        onBackClicked = { viewModel.setActiveChat(null) },
                        isDesktopLayout = true
                    )
                }
            }
        } else {
            // Mobile standard single-pane switcher
            AnimatedContent(
                targetState = uiState.activeChatPeerIp,
                transitionSpec = {
                    if (targetState != null) {
                        (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> -width } + fadeOut())
                    } else {
                        (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> width } + fadeOut())
                    }
                },
                label = "ScreenTransition"
            ) { activeIp ->
                if (activeIp == null) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = SleekSurface
                    ) {
                        SidebarControlPanel(
                            uiState = uiState,
                            viewModel = viewModel
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(SleekDark)
                    ) {
                        ChatWorkspace(
                            uiState = uiState,
                            messages = messages,
                            viewModel = viewModel,
                            onBackClicked = { viewModel.setActiveChat(null) },
                            isDesktopLayout = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SidebarControlPanel(
    uiState: MessengerUiState,
    viewModel: MessengerViewModel,
    modifier: Modifier = Modifier
) {
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showAddPeerDialog by remember { mutableStateOf(false) }
    
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // App Identity Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Lock Logo",
                tint = SleekAccent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "CRYPT-LINK // P2P",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = SleekAccent,
                    letterSpacing = 1.sp
                )
            )
        }

        // Live Server Instance Status Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SleekCard),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SleekBorder, RoundedCornerShape(12.dp))
                .padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // User Display Name Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "User Icon",
                            tint = SleekAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = uiState.ownName,
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekTextPrimary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = { showEditNameDialog = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Name",
                            tint = SleekTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Network IP Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "ADDR: ${uiState.ownIp}:${uiState.ownPort}",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = SleekTextPrimary
                        )
                    )
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh IP",
                        tint = SleekAccent,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { viewModel.resolveLocalIp() }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Public Fingerprint Row
                Text(
                    text = "SIG: ${uiState.ownFingerprint}",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = SleekTextSecondary
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Listening Status Dot
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (uiState.isServerRunning) SleekAccent else Color.Red)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (uiState.isServerRunning) "LOCAL SERVER ONLINE" else "SERVER OFFLINE",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (uiState.isServerRunning) SleekAccent else Color.Red
                        )
                    )
                }
            }
        }

        // Subnet Scanner Radar Section
        Card(
            colors = CardDefaults.cardColors(containerColor = SleekCard),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (uiState.isScanning) SleekAccent else SleekBorder, RoundedCornerShape(12.dp))
                .padding(bottom = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "DISCOVERY RADAR",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekTextPrimary
                        )
                    )
                    if (uiState.isScanning) {
                        Text(
                            text = "${(uiState.scanProgress * 100).toInt()}%",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = SleekAccent,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (uiState.isScanning) {
                    LinearProgressIndicator(
                        progress = { uiState.scanProgress },
                        color = SleekAccent,
                        trackColor = SleekBorder,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "SYNCHRONIZING WITH SUBNET...",
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = SleekTextSecondary
                        )
                    )
                } else {
                    Button(
                        onClick = { viewModel.scanSubnet() },
                        colors = ButtonDefaults.buttonColors(containerColor = SleekDark),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SleekAccent, RoundedCornerShape(8.dp))
                            .testTag("scan_button"),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Radar Scan",
                            tint = SleekAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SCAN LOCAL NETWORK",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekAccent
                            )
                        )
                    }
                }
            }
        }

        // Active Chats / Contacts List
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            Text(
                text = "SECURE PEERS",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = SleekTextSecondary,
                    letterSpacing = 0.5.sp
                )
            )
            IconButton(
                onClick = { showAddPeerDialog = true },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New chat",
                    tint = SleekAccent,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Chats Scrollable
        if (uiState.peers.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(1.dp, SleekBorder, RoundedCornerShape(8.dp))
                    .background(SleekDark),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Empty Peers",
                        tint = SleekTextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No Peers Discovered",
                        style = TextStyle(
                            fontSize = 13.sp,
                            color = SleekTextSecondary,
                            textAlign = TextAlign.Center
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Trigger a Scan above, or manually add a local peer's IP address.",
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = SleekTextSecondary.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        ),
                        lineHeight = 15.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.peers, key = { it.ip }) { peer ->
                    PeerContactCard(
                        peer = peer,
                        isSelected = uiState.activeChatPeerIp == peer.ip,
                        onClick = { viewModel.setActiveChat(peer.ip) },
                        onRemove = { viewModel.removePeer(peer.ip) }
                    )
                }
            }
        }
    }

    // Inline Dialog: Change Display Name & Listening Port
    if (showEditNameDialog) {
        var tempName by remember { mutableStateOf(uiState.ownName) }
        var tempAvatar by remember { mutableStateOf(uiState.ownAvatar) }
        var tempPort by remember { mutableStateOf(uiState.ownPort.toString()) }
        var portError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = {
                Text(
                    text = "EDIT LOCAL PROFILE",
                    style = TextStyle(fontFamily = FontFamily.Monospace, color = SleekAccent)
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "CHOOSE AVATAR",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekAccent
                        )
                    )
                    
                    val avatarOptions = listOf("👤", "🐱", "🐶", "🦊", "🦁", "🐨", "🐼", "🚀", "🤖", "🎨", "👾", "🦄", "🐧", "🦉")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(SleekDark)
                                .border(2.dp, SleekAccent, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = tempAvatar, fontSize = 24.sp)
                        }
                        
                        Box(modifier = Modifier.height(24.dp).width(1.dp).background(SleekBorder))
                        
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(avatarOptions.size) { index ->
                                val avatar = avatarOptions[index]
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(if (tempAvatar == avatar) SleekCard else Color.Transparent)
                                        .border(
                                            1.dp,
                                            if (tempAvatar == avatar) SleekAccent else SleekBorder.copy(alpha = 0.5f),
                                            CircleShape
                                        )
                                        .clickable { tempAvatar = avatar },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = avatar, fontSize = 18.sp)
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = tempName,
                        onValueChange = { tempName = it },
                        label = { Text("Display Name") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SleekAccent,
                            unfocusedBorderColor = SleekBorder,
                            focusedLabelColor = SleekAccent
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tempPort,
                        onValueChange = { 
                            tempPort = it
                            val p = it.toIntOrNull()
                            if (p == null || p !in 1024..65535) {
                                portError = "Port must be between 1024 and 65535"
                            } else {
                                portError = null
                            }
                        },
                        label = { Text("Listening Port") },
                        isError = portError != null,
                        supportingText = {
                            if (portError != null) {
                                Text(portError!!, color = Color.Red, fontSize = 11.sp)
                            } else {
                                Text("Port for receiving incoming connections", fontSize = 11.sp, color = SleekTextSecondary)
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SleekAccent,
                            unfocusedBorderColor = SleekBorder,
                            focusedLabelColor = SleekAccent
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = portError == null && tempName.isNotBlank() && tempPort.isNotBlank(),
                    onClick = {
                        viewModel.updateOwnName(tempName)
                        viewModel.updateOwnAvatar(tempAvatar)
                        val p = tempPort.toIntOrNull()
                        if (p != null) {
                            viewModel.updateOwnPort(p)
                        }
                        showEditNameDialog = false
                    }
                ) {
                    Text("SAVE", color = SleekAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) {
                    Text("CANCEL", color = SleekTextSecondary)
                }
            },
            containerColor = SleekCard
        )
    }

    // Inline Dialog: Add Local Network Peer
    if (showAddPeerDialog) {
        var ipInput by remember { mutableStateOf("") }
        var aliasInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddPeerDialog = false },
            title = {
                Text(
                    text = "MANUALLY ADD PEER",
                    style = TextStyle(fontFamily = FontFamily.Monospace, color = SleekAccent)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Enter the local subnet IP, or public IP:PORT (e.g. 12.34.56.78:12120) of another device.",
                        style = TextStyle(fontSize = 12.sp, color = SleekTextSecondary)
                    )
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        label = { Text("Peer IP / Address & Port") },
                        placeholder = { Text("e.g. 192.168.1.102 or 12.34.56.78:12120") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Phone,
                            imeAction = ImeAction.Next
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SleekAccent,
                            unfocusedBorderColor = SleekBorder,
                            focusedLabelColor = SleekAccent
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = aliasInput,
                        onValueChange = { aliasInput = it },
                        label = { Text("Alias Name (Optional)") },
                        placeholder = { Text("Bob's Tablet") },
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SleekAccent,
                            unfocusedBorderColor = SleekBorder,
                            focusedLabelColor = SleekAccent
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (ipInput.isNotBlank()) {
                            val chosenAlias = aliasInput.ifBlank { "Node_${ipInput.takeLast(3)}" }
                            viewModel.connectToPeer(ipInput.trim(), chosenAlias)
                            showAddPeerDialog = false
                        }
                    },
                    modifier = Modifier.testTag("submit_button")
                ) {
                    Text("CONNECT & SAVE", color = SleekAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPeerDialog = false }) {
                    Text("CANCEL", color = SleekTextSecondary)
                }
            },
            containerColor = SleekCard
        )
    }
}

@Composable
fun PeerContactCard(
    peer: Peer,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) SleekCard else Color.Transparent
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isSelected) SleekAccent else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Interactive Emoji Avatar Circle with Corner Online Beacon overlay
                Box(
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(SleekDark)
                            .border(1.dp, SleekBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = peer.avatar,
                            style = TextStyle(fontSize = 20.sp)
                        )
                    }
                    // Corner Beacon Badge
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(SleekDark)
                            .padding(1.5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(if (peer.isOnline) SleekAccent else Color.Gray)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column {
                    Text(
                        text = peer.alias,
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) SleekAccent else SleekTextPrimary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = peer.ip,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = SleekTextSecondary
                        )
                    )
                }
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete contact",
                    tint = SleekTextSecondary.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
fun ChatWorkspace(
    uiState: MessengerUiState,
    messages: List<ChatMessage>,
    viewModel: MessengerViewModel,
    onBackClicked: () -> Unit,
    isDesktopLayout: Boolean
) {
    val activeIp = uiState.activeChatPeerIp
    val chatPeer = uiState.peers.find { it.ip == activeIp }

    if (activeIp == null || chatPeer == null) {
        // Desktop Static Empty terminal state
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SleekDark),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .padding(32.dp)
            ) {
                // Large glowing lock icon
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .border(1.dp, SleekAccent.copy(alpha = 0.4f), CircleShape)
                        .background(SleekAccent.copy(alpha = 0.05f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Secure Lock Status",
                        tint = SleekAccent,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "DECENTRALIZED CRYPTO TERMINAL",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = SleekTextPrimary,
                        letterSpacing = 1.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(14.dp))

                Divider(color = SleekBorder, thickness = 1.dp, modifier = Modifier.fillMaxWidth(0.4f))

                Spacer(modifier = Modifier.height(14.dp))

                // Status system terminal info log
                Card(
                    colors = CardDefaults.cardColors(containerColor = SleekSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, SleekBorder, RoundedCornerShape(8.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "> SECURE CHANNEL: ACTIVE",
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = SleekAccent)
                        )
                        Text(
                            text = "> ASYMMETRIC KEY: RSA-2048 BIT",
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = SleekAccent)
                        )
                        Text(
                            text = "> SYMMETRIC SESSION: AES-256 (CBC/PKCS5)",
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = SleekAccent)
                        )
                        Text(
                            text = "> METADATA STORAGE: NONE (LOCAL ONLY)",
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = SleekTextSecondary)
                        )
                        Text(
                            text = "> LISTENING CAPABILITY: LOCAL_NETWORK_SOCKETS",
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = SleekTextSecondary)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Select a secure peer from the side panel or run a radar scan to initialize immediate E2EE real-time communication.",
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = SleekTextSecondary,
                        textAlign = TextAlign.Center
                    ),
                    lineHeight = 18.sp
                )
            }
        }
    } else {
        // Active Chat screen
        val context = LocalContext.current
        var messageTextInput by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        
        val filePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                val (fileName, fileSize) = getFileNameAndSize(context, uri)
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val fileType = if (mimeType.startsWith("image/")) "image" else "file"
                viewModel.sendFileOrImage(uri, fileName, fileType, fileSize)
            }
        }

        // Auto-scroll to bottom of conversation
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Chat Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SleekSurface)
                    .border(1.dp, SleekBorder)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isDesktopLayout) {
                        IconButton(onClick = onBackClicked) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = SleekAccent
                            )
                        }
                    }
                    
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(SleekDark)
                            .border(1.dp, SleekBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = chatPeer.avatar,
                            style = TextStyle(fontSize = 18.sp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = chatPeer.alias,
                                style = TextStyle(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekTextPrimary
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (chatPeer.isOnline) SleekAccent else Color.DarkGray)
                            )
                        }
                        Text(
                            text = "IP: ${chatPeer.ip}  •  FPR: ${if (chatPeer.publicKey.isNotBlank()) EncryptionManager.getFingerprint(chatPeer.publicKey) else "Acquiring..."}",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = SleekTextSecondary
                            )
                        )
                    }
                }

                // Chat Controls
                Row {
                    IconButton(onClick = { viewModel.clearCurrentChat() }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear History",
                            tint = SleekTextSecondary
                        )
                    }
                }
            }

            // Message Board Scroll Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
            ) {
                if (messages.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "E2E Secure Channel",
                            tint = SleekAccent.copy(alpha = 0.3f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "AES/RSA Encypted Tunnel Activated",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = SleekAccent,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = "All keystrokes leaving this terminal are fully ciphered.",
                            style = TextStyle(fontSize = 11.sp, color = SleekTextSecondary)
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            ChatMessageBubble(msg = msg)
                        }
                    }
                }
            }

            // Interactive peer typing notification
            if (uiState.activeChatPeerTyping) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${chatPeer.alias} is typing",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = SleekAccent,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    TypingAnimationDots()
                }
            }

            // Chat Input Bar (Sleek pill design matching HTML scheme)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SleekDark)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Secure Attachment Button
                IconButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(SleekCard)
                        .border(1.dp, SleekBorder, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Attach File or Picture",
                        tint = SleekAccent,
                        modifier = Modifier.size(22.dp)
                    )
                }

                OutlinedTextField(
                    value = messageTextInput,
                    onValueChange = {
                        messageTextInput = it
                        // Fire typing notification state
                        if (it.isNotEmpty()) viewModel.sendTypingState(true)
                    },
                    placeholder = { Text("Secure message...", color = SleekTextSecondary.copy(alpha = 0.7f)) },
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send,
                        keyboardType = KeyboardType.Text
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (messageTextInput.isNotBlank()) {
                                viewModel.sendEncryptedMessage(messageTextInput)
                                viewModel.sendTypingState(false)
                                messageTextInput = ""
                            }
                        }
                    ),
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SleekCard,
                        unfocusedContainerColor = SleekCard,
                        focusedBorderColor = SleekBorder,
                        unfocusedBorderColor = SleekBorder,
                        focusedTextColor = SleekTextPrimary,
                        unfocusedTextColor = SleekTextPrimary
                    ),
                    maxLines = 4,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )

                IconButton(
                    onClick = {
                        if (messageTextInput.isNotBlank()) {
                            viewModel.sendEncryptedMessage(messageTextInput)
                            viewModel.sendTypingState(false)
                            messageTextInput = ""
                        }
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(SleekAccent)
                        .testTag("send_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = SleekContrastText,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}@Composable
fun ChatMessageBubble(msg: ChatMessage) {
    var expandedPayload by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (msg.isFromMe) Alignment.End else Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (msg.isFromMe) Arrangement.End as Arrangement.Horizontal else Arrangement.Start as Arrangement.Horizontal,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (!msg.isFromMe) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "E2EE Secured",
                    tint = SleekAccent,
                    modifier = Modifier
                        .size(12.dp)
                        .padding(end = 2.dp)
                )
            }
            Text(
                text = if (msg.isFromMe) "You" else msg.senderName,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (msg.isFromMe) SleekAccent else SleekAccent
                )
            )
            if (msg.isFromMe) {
                Spacer(modifier = Modifier.width(3.dp))
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "E2EE Secured",
                    tint = SleekAccent,
                    modifier = Modifier.size(10.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        // Decrypted cleartext bubble - styled strictly as Sleek Interface:
        // - From Me: bg SleekAccent, text SleekContrastText (solid, rounded and padding)
        // - From Peer: bg SleekCard, text SleekTextPrimary
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (msg.isFromMe) 16.dp else 4.dp,
                        bottomEnd = if (msg.isFromMe) 4.dp else 16.dp
                    )
                )
                .background(if (msg.isFromMe) SleekAccent else SleekCard)
                .border(
                    1.dp,
                    if (msg.isFromMe) SleekAccent else SleekBorder,
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (msg.isFromMe) 16.dp else 4.dp,
                        bottomEnd = if (msg.isFromMe) 4.dp else 16.dp
                    )
                )
                .clickable { expandedPayload = !expandedPayload }
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column {
                if (msg.fileType == "image" && msg.localFilePath != null) {
                    val bitmap = remember(msg.localFilePath) {
                        try {
                            BitmapFactory.decodeFile(msg.localFilePath)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = msg.fileName ?: "Image Attachment",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, SleekBorder, RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    } else {
                        Text(
                            text = "⚠️ Picture data encrypted securely.",
                            style = TextStyle(fontSize = 13.sp, color = if (msg.isFromMe) SleekContrastText else SleekAccent)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                } else if (msg.fileType == "file" && msg.localFilePath != null) {
                    val context = LocalContext.current
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SleekDark),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .border(1.dp, SleekBorder, RoundedCornerShape(8.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = msg.fileName ?: "File Attachment",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekAccent
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Size: ${formatFileSize(msg.fileSize)}",
                                    style = TextStyle(
                                        fontSize = 11.sp,
                                        color = SleekTextSecondary
                                    )
                                )
                            }
                            IconButton(
                                onClick = {
                                    val srcFile = File(msg.localFilePath)
                                    if (srcFile.exists()) {
                                        try {
                                            val dstFile = File(
                                                context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                                                msg.fileName ?: "downloaded_file"
                                            )
                                            srcFile.inputStream().use { input ->
                                                dstFile.outputStream().use { output ->
                                                    input.copyTo(output)
                                                }
                                            }
                                            Toast.makeText(context, "Saved to Downloads: ${dstFile.name}", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "File does not exist locally", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Export File",
                                    tint = SleekAccent
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Text(
                    text = msg.messageText,
                    style = TextStyle(
                        fontSize = 15.sp,
                        color = if (msg.isFromMe) SleekContrastText else SleekTextPrimary,
                        fontWeight = if (msg.isFromMe) FontWeight.Medium else FontWeight.Normal
                    ),
                    lineHeight = 20.sp
                )
                
                // Clickable prompt showing Cipher expand possibility
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = if (expandedPayload) "COLLAPSE AUDIT" else "VERIFY E2E CIPHER",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (msg.isFromMe) SleekContrastText.copy(alpha = 0.8f) else SleekAccent
                        )
                    )
                    Icon(
                        imageVector = if (expandedPayload) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand audit",
                        tint = if (msg.isFromMe) SleekContrastText.copy(alpha = 0.8f) else SleekAccent,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }

        // Expanded Decryption/Cryptographic Detail Panel
        AnimatedVisibility(
            visible = expandedPayload,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SleekDark),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(vertical = 4.dp)
                    .border(1.dp, SleekBorder.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "🔒 CRYPTO PACKET DECRYPTION VERIFICATION:",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = SleekAccent,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    TextDetailItem(label = "CIPHER SUITE", value = "AES-256-CBC with PKCS5 padding")
                    TextDetailItem(label = "TRANS PROTO", value = "Socket Event Broadcast (JSON-formatted frame)")
                    TextDetailItem(
                        label = "RSA SESSION KEY (Cipher)",
                        value = msg.encryptedAesKey.take(30) + "... [RSA-2048 Encrypted]"
                    )
                    TextDetailItem(label = "IV (Base64)", value = msg.iv)
                    TextDetailItem(
                        label = "PAYLOAD CIPHERED (Base64)",
                        value = msg.encryptedData.take(50) + "..."
                    )
                    TextDetailItem(
                        label = "LOCAL DECRYPTED",
                        value = "\"${msg.messageText}\""
                    )
                }
            }
        }
    }
}

@Composable
fun TextDetailItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "$label :",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = SleekPurpleCode
            )
        )
        Text(
            text = value,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = SleekTextSecondary
            )
        )
    }
}

@Composable
fun TypingAnimationDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    
    val dot1 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, delayMillis = 0, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val dot2 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, delayMillis = 150, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val dot3 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, delayMillis = 300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(SleekAccent.copy(alpha = dot1)))
        Spacer(modifier = Modifier.width(3.dp))
        Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(SleekAccent.copy(alpha = dot2)))
        Spacer(modifier = Modifier.width(3.dp))
        Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(SleekAccent.copy(alpha = dot3)))
    }
}

fun getFileNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
    var name = "unknown"
    var size = 0L
    try {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
                val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex != -1) {
                    size = it.getLong(sizeIndex)
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "Error querying OpenableColumns", e)
    }
    if (name == "unknown") {
        name = uri.path?.substringAfterLast('/') ?: "file"
    }
    return Pair(name, size)
}

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    if (digitGroups !in units.indices) return "$size B"
    return String.format(java.util.Locale.US, "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
