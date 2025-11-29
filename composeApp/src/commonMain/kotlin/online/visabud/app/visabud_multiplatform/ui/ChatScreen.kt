package online.visabud.app.visabud_multiplatform.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
import online.visabud.app.visabud_multiplatform.doc.documentPipeline
import online.visabud.app.visabud_multiplatform.doc.mapToJson
import online.visabud.app.visabud_multiplatform.data.DataModule
import online.visabud.app.visabud_multiplatform.data.DocumentMeta
import online.visabud.app.visabud_multiplatform.platform.rememberPlatformPickers

private enum class Sender { USER, VISABUD }
private data class ChatMessage(val text: String, val sender: Sender)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(paddingValues: PaddingValues) {
    // Inline attachments instead of popups
    data class Attached(val path: String, val kind: String)
    val attachments = remember { mutableStateListOf<Attached>() }
    var message by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var isVisaBudTyping by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    
    // Audio recording state
    var isRecording by remember { mutableStateOf(false) }

    // Snackbar host for toast-like messages on all platforms
    val snackbarHostState = remember { SnackbarHostState() }
    // Show a visible progress bar while the local model is downloading
    var isDownloading by remember { mutableStateOf(false) }


    // Local AI client (Android/iOS use Cactus; web shows stub)
    val client = remember { aiChatClient() }
    // Audio recorder (expect/actual per platform)
    val recorder = remember { online.visabud.app.visabud_multiplatform.ai.audioRecorder() }

    // Ensure model is ready on first open (no simulated messages)
    // Platform pickers
    val pickers = rememberPlatformPickers { picked ->
        if (picked.isNotBlank()) attachments.add(Attached(picked, "file"))
        showBottomSheet = false
        scope.launch { snackbarHostState.showSnackbar("Attached: " + picked.substringAfterLast('/')) }
    }

    LaunchedEffect(Unit) {
        try {
            val alreadyDownloaded = client.isModelDownloaded()
            if (!alreadyDownloaded) {
                // Only notify if we are about to download
                isDownloading = true
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
        } finally {
            isDownloading = false
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
                    val userTextOriginal = message
                    if (userTextOriginal.isBlank()) return@ChatInputBar
                    // Build an attachment note for context
                    val names = attachments.map { it.path.substringAfterLast('/') }
                    val attachNote = if (names.isNotEmpty()) " (attached: ${names.joinToString()})" else ""
                    val userText = userTextOriginal + attachNote
                    messages.add(ChatMessage(userText, Sender.USER))
                    message = ""
                    isVisaBudTyping = true
                    scope.launch {
                        try {
                            // Persist attachments to local repository
                            for (a in attachments.toList()) {
                                try {
                                    val fields = if (a.kind == "file") documentPipeline().extractFields(a.path) else emptyMap()
                                    val doc = DocumentMeta(
                                        id = "doc_" + a.path.hashCode().toString(),
                                        type = a.kind,
                                        filename = a.path.substringAfterLast('/').ifBlank { a.path },
                                        parsedFieldsJson = mapToJson(fields),
                                        uploadedAt = 0L,
                                        encryptedPath = a.path
                                    )
                                    DataModule.documents.add(doc)
                                    // Link to profile
                                    runCatching {
                                        val existing = DataModule.profiles.getProfile() ?: online.visabud.app.visabud_multiplatform.data.UserProfile()
                                        DataModule.profiles.upsertProfile(existing.copy(savedDocs = (existing.savedDocs + doc.id).distinct()))
                                    }
                                } catch (_: Throwable) { /* ignore attachment persistence errors */ }
                            }
                            attachments.clear()
                            // Route through ChatAgent so replies are natural language (no raw JSON)
                            val reply = online.visabud.app.visabud_multiplatform.ai.ChatAgent.handleUserMessage(userText)
                            messages.add(ChatMessage(text = reply.replyText, sender = Sender.VISABUD))
                        } catch (e: Throwable) {
                            val err = e.message ?: "unknown error"
                            snackbarHostState.showSnackbar("Chat failed: $err")
                        } finally {
                            isVisaBudTyping = false
                        }
                    }
                },
                onShowAttachments = { showBottomSheet = true }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isDownloading) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Downloading local model…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
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
            // Inline attachments preview row
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    attachments.forEachIndexed { idx, a ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = a.path.substringAfterLast('/'), style = MaterialTheme.typography.labelMedium)
                                Text(
                                    text = "✕",
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable {
                                            attachments.removeAt(idx)
                                        }
                                        .padding(2.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Scroll to the bottom when a new message is added
        LaunchedEffect(messages.size, attachments.size) {
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
                AttachmentOptions(
                    onPickImage = { pickers.pickImage() },
                    onPickFile = { pickers.pickFile() },
                    onPickAudio = {
                        // Toggle recording; when stopping, run STT and append transcript to input
                        scope.launch {
                            try {
                                if (!isRecording) {
                                    val ok = recorder.start()
                                    isRecording = ok
                                    val msg = if (ok) "Recording… tap Audio again to stop" else "Unable to start recording"
                                    snackbarHostState.showSnackbar(msg)
                                } else {
                                    val path = recorder.stop()
                                    isRecording = false
                                    if (!path.isNullOrBlank()) {
                                        attachments.add(Attached(path, "audio"))
                                        snackbarHostState.showSnackbar("Recorded: ${'$'}{path.substringAfterLast('/')}")
                                        // Auto-transcribe and append to input
                                        try {
                                            val stt = online.visabud.app.visabud_multiplatform.ai.sttClient()
                                            stt.ensureReady()
                                            val text = stt.transcribe(path)
                                            if (!text.isNullOrBlank()) {
                                                message = if (message.isBlank()) text else (message + " \n" + text)
                                                snackbarHostState.showSnackbar("Transcription added to message")
                                            }
                                        } catch (_: Throwable) { /* ignore transcription errors */ }
                                    } else {
                                        snackbarHostState.showSnackbar("Recording stopped")
                                    }
                                }
                            } catch (e: Throwable) {
                                val msg = e.message ?: "unknown"
                                snackbarHostState.showSnackbar("Audio error: $msg")
                                isRecording = false
                                runCatching { recorder.stop() }
                            }
                        }
                    },
                ) {
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
            if (!isUser) {
                AssistantStructuredContent(message.text)
            } else {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(12.dp)
                )
            }
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
private fun AttachmentOptions(
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onPickAudio: () -> Unit,
    onOptionSelected: () -> Unit = {}
) {
    Column(modifier = Modifier.padding(bottom = 32.dp)) {
        AttachmentOptionItem(
            icon = Icons.Outlined.Image,
            label = "Image",
            onClick = {
                onPickImage(); onOptionSelected()
            }
        )
        AttachmentOptionItem(
            icon = Icons.Outlined.Mic,
            label = "Audio",
            onClick = { onPickAudio(); onOptionSelected() }
        )
        AttachmentOptionItem(
            icon = Icons.Outlined.AttachFile,
            label = "File",
            onClick = {
                onPickFile(); onOptionSelected()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentOptionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = label) },
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .clickable { onClick() }
    )
}

@Preview
@Composable
private fun ChatScreenPreview() {
    online.visabud.app.visabud_multiplatform.theme.VisabudTheme {
        ChatScreen(PaddingValues())
    }
}


@Composable
private fun AssistantStructuredContent(text: String) {
    // Minimal structured content renderer for assistant messages.
    // For now, just display the text with padding. Can be enhanced to render
    // JSON blocks and Sources sections with better formatting.
    Text(text = text, modifier = Modifier.padding(12.dp))
}
