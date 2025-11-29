package online.visabud.app.visabud_multiplatform.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview
import online.visabud.app.visabud_multiplatform.ai.ChatMsg
import online.visabud.app.visabud_multiplatform.ai.aiChatClient
import online.visabud.app.visabud_multiplatform.ai.showToast

private enum class Sender { USER, VISABUD }
private data class ChatMessage(val text: String, val sender: Sender)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(paddingValues: PaddingValues) {
    var message by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var isVisaBudTyping by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // Snackbar host for toast-like messages on all platforms
    val snackbarHostState = remember { SnackbarHostState() }

    // Local AI client (Android/iOS use Cactus; web shows stub)
    val client = remember { aiChatClient() }

    // Ensure model is ready on first open (no simulated messages)
    LaunchedEffect(Unit) {
        try {
            val alreadyDownloaded = client.isModelDownloaded()
            if (!alreadyDownloaded) {
                // Only notify if we are about to download
                showToast("Downloading model…")
                snackbarHostState.showSnackbar("Model download started")
            }
            client.ensureReady()
            if (!alreadyDownloaded) {
                showToast("Model ready")
                snackbarHostState.showSnackbar("Model ready. You can start chatting.")
            }
        } catch (e: Throwable) {
            snackbarHostState.showSnackbar("Failed to init model: ${e.message ?: "unknown"}")
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .imePadding(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            ChatInputBar(
                message = message,
                onMessageChange = { message = it },
                onSendMessage = {
                    val userText = message
                    if (userText.isBlank()) return@ChatInputBar
                    messages.add(ChatMessage(userText, Sender.USER))
                    message = ""
                    isVisaBudTyping = true
                    scope.launch {
                        try {
                            // Build chat history for the model
                            val history: List<ChatMsg> = messages.map {
                                val role = if (it.sender == Sender.USER) "user" else "assistant"
                                ChatMsg(content = it.text, role = role)
                            }
                            val reply = client.send(history)
                            messages.add(ChatMessage(reply, Sender.VISABUD))
                        } catch (e: Throwable) {
                            snackbarHostState.showSnackbar("Chat failed: ${e.message ?: "unknown error"}")
                        } finally {
                            isVisaBudTyping = false
                        }
                    }
                },
                onShowAttachments = { showBottomSheet = true }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages) { msg ->
                MessageBubble(msg)
            }
            if (isVisaBudTyping) {
                item { TypingIndicator() }
            }
        }

        // Scroll to the bottom when a new message is added
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.scrollToItem(messages.lastIndex)
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
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.sender == Sender.USER
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val colors = if (isUser) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    } else {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(16.dp),
            colors = colors
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun TypingIndicator() {
    val dotSize = 8.dp
    val infinite = rememberInfiniteTransition(label = "typing")
    val bounce by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bounce"
    )

    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(modifier = Modifier.size(dotSize).offset(y = bounce.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        Box(modifier = Modifier.size(dotSize).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        Box(modifier = Modifier.size(dotSize).offset(y = (-bounce).dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
    }
}

@Composable
private fun ChatInputBar(
    message: String,
    onMessageChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onShowAttachments: () -> Unit
) {
    Surface(shadowElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onShowAttachments) {
                Icon(Icons.Default.Add, contentDescription = "Attach File")
            }

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
                    value = message,
                    onValueChange = onMessageChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    decorationBox = { innerTextField ->
                        if (message.isEmpty()) {
                            Text("Type a message...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        innerTextField()
                    }
                )
            }

            IconButton(
                onClick = onSendMessage,
                enabled = message.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
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
