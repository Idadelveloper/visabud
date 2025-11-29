package online.visabud.app.visabud_multiplatform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import online.visabud.app.visabud_multiplatform.ai.ChatMsg
import online.visabud.app.visabud_multiplatform.ai.aiChatClient
import org.jetbrains.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(paddingValues: PaddingValues) {
    var input by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var showBottomSheet by remember { mutableStateOf(false) }

    val messages = remember { mutableStateListOf<ChatMsg>() }
    var isLoading by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val client = remember { aiChatClient() }

    // Initialize model on first open to start download of local-qwen3-0.6
    LaunchedEffect(Unit) {
        try {
            client.ensureReady()
            isLoading = false
        } catch (t: Throwable) {
            error = t.message ?: "Initialization failed"
            isLoading = false
        }
    }

    fun sendMessage() {
        val content = input.trim()
        if (content.isEmpty() || isSending) return
        input = ""
        error = null
        val userMsg = ChatMsg(content = content, role = "user")
        messages.add(userMsg)
        isSending = true
        scope.launch {
            try {
                // Ensure model is ready (no-op if already loaded)
                client.ensureReady()
                val reply = client.send(messages.toList())
                messages.add(ChatMsg(content = reply, role = "assistant"))
            } catch (t: Throwable) {
                error = t.message ?: "Send failed"
            } finally {
                isSending = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Status banner / error
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (error != null) {
                AssistChip(onClick = {
                    // Retry initialize/download when user taps the chip
                    error = null
                    scope.launch {
                        try {
                            isLoading = true
                            client.ensureReady()
                        } catch (t: Throwable) {
                            error = t.message ?: "Initialization failed"
                        } finally {
                            isLoading = false
                        }
                    }
                }, label = { Text(error!!) })
            }

            // Chat messages area
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(messages) { msg ->
                    val bg = if (msg.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                    val onBg = if (msg.role == "user") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                    Surface(color = bg, shape = RoundedCornerShape(12.dp)) {
                        Text(
                            text = msg.content,
                            color = onBg,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            // Chat input area
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Attachment button
                    IconButton(onClick = { showBottomSheet = true }, enabled = !isSending) {
                        Icon(Icons.Default.Add, contentDescription = "Attach File")
                    }

                    // Text input
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(24.dp)
                            )
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                            decorationBox = { innerTextField ->
                                if (input.isEmpty()) {
                                    Text("Type a message...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                innerTextField()
                            }
                        )
                    }

                    // Send button
                    IconButton(
                        onClick = { sendMessage() },
                        enabled = input.isNotBlank() && !isSending
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        }

        // Attachment Bottom Sheet
        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                sheetState = sheetState
            ) {
                AttachmentOptions {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) {
                            showBottomSheet = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentOptions(onOptionSelected: () -> Unit) {
    Column(modifier = Modifier.padding(bottom = 32.dp)) {
        AttachmentOptionItem(
            icon = Icons.Outlined.Image,
            label = "Image",
            onClick = onOptionSelected // TODO: Implement image picking
        )
        AttachmentOptionItem(
            icon = Icons.Outlined.Mic,
            label = "Audio",
            onClick = onOptionSelected // TODO: Implement audio recording
        )
        AttachmentOptionItem(
            icon = Icons.Outlined.AttachFile,
            label = "File",
            onClick = onOptionSelected // TODO: Implement file picking
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentOptionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = label) },
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Preview
@Composable
private fun ChatScreenPreview() {
    online.visabud.app.visabud_multiplatform.theme.VisabudTheme {
        ChatScreen(PaddingValues())
    }
}
