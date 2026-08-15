# -*- coding: utf-8 -*-
"""One-shot: base strings + ViewModel + Metadata screen UI for the phone key handover."""
import io

def edit(path, pairs):
    s = io.open(path, encoding="utf-8").read()
    for old, new in pairs:
        assert s.count(old) == 1, (path, old[:70], s.count(old))
        s = s.replace(old, new)
    io.open(path, "w", encoding="utf-8").write(s)


# ------------------------------------------------------------- companion strings
p = "app/src/main/res/values/strings_setup.xml"
s = io.open(p, encoding="utf-8").read()
anchor = '<string name="companion_add_page_title">'
i = s.index(anchor)
line_start = s.rindex("\n", 0, i) + 1
block = (
    "    <!-- Translators: Browser tab title for the page a phone uses to send a TMDB key to the TV. -->\n"
    '    <string name="companion_tmdb_page_title">Send TMDB key</string>\n'
    "    <!-- Translators: Heading on that page. -->\n"
    '    <string name="companion_tmdb_heading">Send your TMDB key</string>\n'
    "    <!-- Translators: Instructions on that page. -->\n"
    '    <string name="companion_tmdb_description">Sign in to TMDB below, copy your API key (v3 auth), '
    "paste it here and send it to your TV. A personal key is free and has practically no daily limit.</string>\n"
    "    <!-- Translators: Link to TMDB's API settings page. -->\n"
    '    <string name="companion_tmdb_get_key">Open TMDB API settings</string>\n'
    "    <!-- Translators: Error when the pasted text does not look like a TMDB API key. -->\n"
    '    <string name="companion_tmdb_invalid">That does not look like a TMDB API key. Copy the '
    '"API Key (v3 auth)" value.</string>\n'
    "    <!-- Translators: Confirmation shown on the phone after the key reaches the TV. -->\n"
    '    <string name="companion_tmdb_sent_body">Your TMDB key is now on the TV. Press Save there to '
    "start using it.</string>\n"
    "    <!-- Translators: Link back to the form to send another key. -->\n"
    '    <string name="companion_tmdb_sent_link">Send a different key</string>\n'
)
io.open(p, "w", encoding="utf-8").write(s[:line_start] + block + s[line_start:])

# ------------------------------------------------------------- settings strings
p = "app/src/main/res/values/strings_settings.xml"
s = io.open(p, encoding="utf-8").read()
anchor = '    <string name="settings_tmdb_api_key">'
assert s.count(anchor) == 1
block = (
    "    <!-- Translators: Row that opens the phone handover, so the key need not be typed on a remote. -->\n"
    '    <string name="settings_metadata_key_from_phone">Get key from your phone</string>\n'
    "    <!-- Translators: Description of that row. -->\n"
    '    <string name="settings_metadata_key_from_phone_desc">Scan a code with your phone, sign in to '
    "TMDB there, and send the key across. Nothing to type on the remote.</string>\n"
    "    <!-- Translators: Toast shown on the TV when a key arrives from the phone. -->\n"
    '    <string name="settings_metadata_key_received">TMDB key received from your phone</string>\n'
)
io.open(p, "w", encoding="utf-8").write(s.replace(anchor, block + anchor))

# ------------------------------------------------------------- ViewModel
edit("app/src/main/java/tv/own/owntv/features/settings/SettingsViewModel.kt", [(
    "    fun startRemoteImageListener(port: Int) = companion.startForImageUpload(port)",
    "    fun startRemoteImageListener(port: Int) = companion.startForImageUpload(port)\n"
    "\n"
    "    /** TMDB API keys handed over from a phone, so a 32-character key never has to be typed on a remote. */\n"
    "    val remoteTmdbKeys get() = companion.tmdbKeys\n"
    "\n"
    "    fun startRemoteTmdbKeyListener(port: Int) = companion.startForTmdbKey(port)",
)])

# ------------------------------------------------------------- Metadata screen
p = "app/src/main/java/tv/own/owntv/features/settings/MetadataSettingsScreen.kt"
s = io.open(p, encoding="utf-8").read()


def rep(old, new):
    global s
    assert s.count(old) == 1, (old[:80], s.count(old))
    s = s.replace(old, new)


rep(
    "    var showModePicker by remember { mutableStateOf(false) }",
    "    var showModePicker by remember { mutableStateOf(false) }\n"
    "    var showPhoneHandover by remember { mutableStateOf(false) }",
)

# the row, directly above the key field so the connection is obvious
rep(
    """            Spacer(Modifier.height(12.dp))
            OwnTVTextField(
                value = key,
                onValueChange = { key = it },
                label = stringResource(R.string.settings_tmdb_api_key),""",
    """            Spacer(Modifier.height(12.dp))
            // Typing a 32-character key with a D-pad is the real reason people stay on the shared
            // service, so offer the phone handover right above the field it fills.
            Row2(
                icon = OwnTVIcon.SETTINGS,
                title = stringResource(R.string.settings_metadata_key_from_phone),
                desc = stringResource(R.string.settings_metadata_key_from_phone_desc),
                chevron = true,
                onClick = { showPhoneHandover = true },
            )
            Spacer(Modifier.height(12.dp))
            OwnTVTextField(
                value = key,
                onValueChange = { key = it },
                label = stringResource(R.string.settings_tmdb_api_key),""",
)

# the dialog, beside the other dialogs so it paints on top
ANCHOR = "\n\n    if (showModePicker) {"
assert s.count(ANCHOR) == 1
s = s.replace(
    ANCHOR,
    """

    if (showPhoneHandover) {
        CompanionKeyDialog(
            state = companionState,
            onStart = vm::startRemoteTmdbKeyListener,
            onStop = vm::stopRemoteListener,
            onDismiss = { showPhoneHandover = false },
        )
    }

    if (showModePicker) {""",
)

io.open(p, "w", encoding="utf-8").write(s)
print("wired")
