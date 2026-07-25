param(
    [string]$AppUrl = "http://localhost:8795/",
    [int]$CdpPort = 9223,
    [string]$ChromePath = "",
    [string]$OutputDir = "$env:TEMP",
    [int]$Width = 1920,
    [int]$Height = 1080,
    [string]$ArtistCollection = "Melodeath",
    [string]$ArtistName = "",
    [string]$TitleCollection = "Soundtracks",
    [switch]$CheckProviderDialog,
    [switch]$CheckReportDialog,
    [switch]$KeepChromeOpen
)

$ErrorActionPreference = "Stop"

$DefaultBrowserPathCandidates = @(
    "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
    "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
    "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
    "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
)
$DefaultCdpProfileName = "musiclib-cdp-profile"
$DefaultBrowserStartupTimeoutSeconds = 10

function Resolve-ChromePath {
    if ($ChromePath -and (Test-Path $ChromePath)) {
        return $ChromePath
    }

    foreach ($candidate in $DefaultBrowserPathCandidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return $candidate
        }
    }

    throw "Chrome or Edge was not found. Pass -ChromePath explicitly."
}

function Test-Cdp {
    param([string]$CdpBase)

    try {
        Invoke-RestMethod -Uri "$CdpBase/json/version" -TimeoutSec 1 | Out-Null
        return $true
    } catch {
        return $false
    }
}

function Start-CdpBrowser {
    param(
        [string]$CdpBase,
        [string]$BrowserPath
    )

    if (Test-Cdp $CdpBase) {
        return $false
    }

    $profileDir = Join-Path $env:TEMP $DefaultCdpProfileName
    $args = @(
        "--headless=new",
        "--remote-debugging-port=$CdpPort",
        "--user-data-dir=$profileDir",
        "--disable-gpu",
        "--no-first-run",
        "--no-default-browser-check",
        "about:blank"
    )

    Start-Process -FilePath $BrowserPath -ArgumentList $args | Out-Null

    $deadline = [DateTime]::UtcNow.AddSeconds($DefaultBrowserStartupTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-Cdp $CdpBase) {
            return $true
        }
        Start-Sleep -Milliseconds 250
    }

    throw "Browser did not open a CDP endpoint at $CdpBase."
}

function Stop-CdpBrowser {
    Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -in @("chrome.exe", "msedge.exe") -and
            ($_.CommandLine -like "*$DefaultCdpProfileName*" -or $_.CommandLine -like "*remote-debugging-port=$CdpPort*")
        } |
        ForEach-Object {
            try {
                Stop-Process -Id $_.ProcessId -Force
            } catch {
                # The process may already be gone.
            }
        }
}

function Add-CacheBust {
    param([string]$Url)

    $separator = if ($Url.Contains("?")) { "&" } else { "?" }
    return "${Url}${separator}cdp_check=$(Get-Date -Format FileDateTimeUniversal)"
}

function Get-PageTarget {
    param([string]$CdpBase)

    $targets = Invoke-RestMethod -Uri "$CdpBase/json/list"
    $page = $targets | Where-Object { $_.type -eq "page" } | Select-Object -First 1
    if ($null -eq $page) {
        $page = Invoke-RestMethod -Method Put -Uri "$CdpBase/json/new"
    }
    return $page
}

function Receive-CdpMessage {
    param([System.Net.WebSockets.ClientWebSocket]$Socket)

    $buffer = New-Object byte[] 1048576
    $chunks = New-Object System.Collections.Generic.List[byte]
    do {
        $segment = [ArraySegment[byte]]::new($buffer)
        $result = $Socket.ReceiveAsync($segment, [Threading.CancellationToken]::None).Result
        if ($result.Count -gt 0) {
            for ($i = 0; $i -lt $result.Count; $i++) {
                $chunks.Add($buffer[$i])
            }
        }
    } while (-not $result.EndOfMessage)
    return [Text.Encoding]::UTF8.GetString($chunks.ToArray())
}

$script:nextCdpId = 0

function Send-Cdp {
    param(
        [System.Net.WebSockets.ClientWebSocket]$Socket,
        [string]$Method,
        $Params = $null
    )

    $script:nextCdpId++
    $message = @{
        id = $script:nextCdpId
        method = $Method
    }
    if ($null -ne $Params) {
        $message.params = $Params
    }

    $json = $message | ConvertTo-Json -Depth 30 -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    $Socket.SendAsync(
        [ArraySegment[byte]]::new($bytes),
        [System.Net.WebSockets.WebSocketMessageType]::Text,
        $true,
        [Threading.CancellationToken]::None
    ).Wait()

    while ($true) {
        $raw = Receive-CdpMessage $Socket
        $response = $raw | ConvertFrom-Json
        if ($response.id -eq $script:nextCdpId) {
            if ($response.error) {
                throw ($response.error | ConvertTo-Json -Compress)
            }
            return $response
        }
    }
}

function Eval-Js {
    param(
        [System.Net.WebSockets.ClientWebSocket]$Socket,
        [string]$Expression
    )

    $response = Send-Cdp $Socket "Runtime.evaluate" @{
        expression = $Expression
        returnByValue = $true
        awaitPromise = $true
    }
    return $response.result.result.value
}

function Wait-ForJs {
    param(
        [System.Net.WebSockets.ClientWebSocket]$Socket,
        [string]$Expression,
        [int]$TimeoutMs = 5000
    )

    $deadline = [DateTime]::UtcNow.AddMilliseconds($TimeoutMs)
    while ([DateTime]::UtcNow -lt $deadline) {
        $value = Eval-Js $Socket $Expression
        if ($value) {
            return $true
        }
        Start-Sleep -Milliseconds 150
    }
    return $false
}

function Save-Screenshot {
    param(
        [System.Net.WebSockets.ClientWebSocket]$Socket,
        [string]$Path
    )

    $response = Send-Cdp $Socket "Page.captureScreenshot" @{
        format = "png"
        captureBeyondViewport = $false
    }
    [IO.File]::WriteAllBytes($Path, [Convert]::FromBase64String($response.result.data))
}

$metricsJs = @'
(() => {
  function box(selector, index = 0) {
    const element = Array.from(document.querySelectorAll(selector))[index];
    if (!element) return null;
    const rect = element.getBoundingClientRect();
    const style = getComputedStyle(element);
    return {
      selector,
      index,
      className: element.className,
      inlineStyle: element.getAttribute('style') || '',
      text: (element.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 90),
      rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height, bottom: rect.bottom },
      clientHeight: element.clientHeight,
      scrollHeight: element.scrollHeight,
      clientWidth: element.clientWidth,
      scrollWidth: element.scrollWidth,
      overflow: `${style.overflow}/${style.overflowX}/${style.overflowY}`,
      display: style.display,
      flex: style.flex,
      height: style.height,
      minHeight: style.minHeight
    };
  }
  return {
    url: location.href,
    viewport: { width: innerWidth, height: innerHeight },
    document: {
      documentClientHeight: document.documentElement.clientHeight,
      documentScrollHeight: document.documentElement.scrollHeight,
      bodyClientHeight: document.body.clientHeight,
      bodyScrollHeight: document.body.scrollHeight
    },
    counts: {
      collectionRows: document.querySelectorAll('.nav-row').length,
      artistRows: document.querySelectorAll('.artists-pane .workspace-row').length,
      titleRows: document.querySelectorAll('.titles-pane .workspace-row').length,
      albumRows: document.querySelectorAll('.albums-pane .workspace-row').length,
      grids: document.querySelectorAll('.workspace-grid').length
    },
    boxes: {
      appMain: box('.app-main'),
      page: box('.collections-workspace'),
      threePane: box('.three-pane'),
      collectionsPane: box('.collections-pane'),
      collectionList: box('.collection-list'),
      collectionEditCard: box('.collection-edit-card'),
      artistsPane: box('.artists-pane'),
      artistsGrid: box('.artists-pane .workspace-grid'),
      albumsPane: box('.albums-pane'),
      albumsGrid: box('.albums-pane .workspace-grid'),
      titlesPane: box('.titles-pane'),
      titlesGrid: box('.titles-pane .workspace-grid')
    }
  };
})()
'@

$cdpBase = "http://127.0.0.1:$CdpPort"
$browserPath = Resolve-ChromePath
$startedBrowser = Start-CdpBrowser $cdpBase $browserPath
$socket = $null

try {
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

    $target = Get-PageTarget $cdpBase
    $socket = [System.Net.WebSockets.ClientWebSocket]::new()
    $socket.ConnectAsync([Uri]$target.webSocketDebuggerUrl, [Threading.CancellationToken]::None).Wait()

    Send-Cdp $socket "Page.enable" | Out-Null
    Send-Cdp $socket "Runtime.enable" | Out-Null
    Send-Cdp $socket "Network.enable" | Out-Null
    Send-Cdp $socket "Network.setCacheDisabled" @{ cacheDisabled = $true } | Out-Null
    Send-Cdp $socket "Emulation.setDeviceMetricsOverride" @{
        width = $Width
        height = $Height
        deviceScaleFactor = 1
        mobile = $false
    } | Out-Null

    Send-Cdp $socket "Page.navigate" @{ url = (Add-CacheBust $AppUrl) } | Out-Null

    if (-not (Wait-ForJs $socket "document.querySelectorAll('.nav-row').length > 0" 8000)) {
        throw "Collections did not render. Check that the app is running and AppUrl is reachable from Windows."
    }
    Start-Sleep -Milliseconds 500
    $initial = Eval-Js $socket $metricsJs
    $initialPath = Join-Path $OutputDir "musiclib-initial.png"
    Save-Screenshot $socket $initialPath

    if ($CheckReportDialog) {
        if (-not (Eval-Js $socket "(() => { const app = document.querySelector('#app')?.__vue_app__; const store = app?.config.globalProperties.`$pinia?._s.get('library'); if (!store) return false; store.`$patch({ statusHistory: [{ id: 1, message: 'Short report', state: 'done', createdAt: '12:00:00', reports: [{ title: 'Short', text: 'short' }] }, { id: 2, message: 'Long report', state: 'done', createdAt: '12:00:01', reports: [{ title: 'Long', text: 'LONG_' + 'x'.repeat(400) }] }] }); document.querySelector('.global-status-bar')?.click(); return true; })()")) {
            throw "Unable to inject report-dialog smoke data."
        }
        if (-not (Wait-ForJs $socket "document.querySelectorAll('.status-history-entry--clickable').length === 2" 3000)) {
            throw "Injected report history did not render."
        }
        Eval-Js $socket "document.querySelector('.status-history-entry--clickable')?.click()" | Out-Null
        if (-not (Wait-ForJs $socket "document.querySelector('.scan-report-dialog__content')?.textContent === 'short'" 3000)) {
            throw "Short report did not open."
        }
        Start-Sleep -Milliseconds 500
        $shortState = Eval-Js $socket "(() => { const rect = document.querySelector('.scan-report-dialog-content').getBoundingClientRect(); return JSON.stringify({ width: rect.width, height: rect.height }); })()"
        $short = $shortState | ConvertFrom-Json
        Eval-Js $socket "document.querySelector('.scan-report-dialog__navigation button:last-of-type')?.click()" | Out-Null
        if (-not (Wait-ForJs $socket "document.querySelector('.scan-report-dialog__content')?.textContent.startsWith('LONG_')" 3000)) {
            throw "Long report did not open."
        }
        $longState = Eval-Js $socket "(() => { const dialog = document.querySelector('.scan-report-dialog-content'); const rect = dialog.getBoundingClientRect(); const scroller = document.querySelector('.scan-report-dialog__scroller'); return JSON.stringify({ width: rect.width, height: rect.height, expectedWidth: Math.min(innerWidth * 0.55, 1500), expectedHeight: innerHeight * 0.75, centerOffsetX: Math.abs(rect.left + rect.width / 2 - innerWidth / 2), centerOffsetY: Math.abs(rect.top + rect.height / 2 - innerHeight / 2), clientWidth: scroller.clientWidth, scrollWidth: scroller.scrollWidth, whiteSpace: getComputedStyle(document.querySelector('.scan-report-dialog__content')).whiteSpace }); })()"
        $long = $longState | ConvertFrom-Json
        if ([Math]::Abs($short.width - $long.width) -gt 0.5 -or [Math]::Abs($short.height - $long.height) -gt 0.5 -or [Math]::Abs($long.width - $long.expectedWidth) -gt 0.5 -or [Math]::Abs($long.height - $long.expectedHeight) -gt 0.5) {
            throw "Report dialog size changed or ignored the 55vw/1500px by 75vh rule: short=$shortState long=$longState."
        }
        if ($long.centerOffsetX -gt 0.5 -or $long.centerOffsetY -gt 0.5) {
            throw "Report dialog is not centered in the browser viewport: $longState"
        }
        if ($long.whiteSpace -ne "pre" -or $long.scrollWidth -le $long.clientWidth) {
            throw "Long report must remain unwrapped and scroll horizontally: $longState"
        }
        Eval-Js $socket "document.querySelector('.scan-report-dialog-content')?.closest('.v-overlay')?.querySelector('.v-overlay__scrim')?.click()" | Out-Null
        Wait-ForJs $socket "document.querySelector('.scan-report-dialog-content') === null" 3000 | Out-Null
    }

    Eval-Js $socket @"
(() => {
  const row = Array.from(document.querySelectorAll('.nav-row'))
    .find((node) => node.textContent.includes('$TitleCollection'));
  if (!row) return false;
  row.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true }));
  const editButton = row.querySelector('.nav-row__actions button');
  if (!editButton) return false;
  editButton.click();
  return true;
})()
"@ | Out-Null
    if (-not (Wait-ForJs $socket "document.querySelector('.collection-edit-card') !== null" 5000)) {
        throw "Collection edit overlay did not render."
    }
    Eval-Js $socket "(() => { const input = document.querySelector('.collection-edit-card input'); if (!input) return false; input.focus(); return true; })()" | Out-Null
    if (-not (Wait-ForJs $socket "(() => { const card = document.querySelector('.collection-edit-card'); const title = card?.querySelector('.v-card-title'); const labels = [...(card?.querySelectorAll('.v-field-label--floating') ?? [])]; if (!card || !title || labels.length === 0) return false; const titleBottom = title.getBoundingClientRect().bottom; const cardTop = card.getBoundingClientRect().top; return labels.every((label) => { const rect = label.getBoundingClientRect(); return rect.top >= titleBottom - 0.5 && rect.top >= cardTop; }); })()" 3000)) {
        throw "Collection edit floating field label overlaps or clips behind the dialog title."
    }
    Start-Sleep -Milliseconds 500
    $collectionEdit = Eval-Js $socket $metricsJs
    $collectionEditPath = Join-Path $OutputDir "musiclib-collection-edit.png"
    Save-Screenshot $socket $collectionEditPath

    Eval-Js $socket "(() => { const cancel = Array.from(document.querySelectorAll('.collection-edit-card button')).find((node) => node.textContent.trim().toLowerCase() === 'cancel'); if (!cancel) return false; cancel.click(); return true; })()" | Out-Null
    Wait-ForJs $socket "document.querySelector('.collection-edit-card') === null" 3000 | Out-Null

    if (-not (Eval-Js $socket "(() => { const row = [...document.querySelectorAll('.nav-row')].find((node) => node.textContent.includes('$TitleCollection')); const button = row?.querySelector('.mdi-trash-can-outline')?.closest('button'); if (!button) return false; button.click(); return true; })()")) {
        throw "Delete Collection confirmation could not be opened."
    }
    if (-not (Wait-ForJs $socket "document.querySelector('.delete-collection-dialog') !== null" 3000)) {
        throw "Delete Collection confirmation did not render."
    }
    Start-Sleep -Milliseconds 500
    if (-not (Eval-Js $socket "(() => { const rect = document.querySelector('.delete-collection-dialog').closest('.v-overlay__content').getBoundingClientRect(); return Math.abs(rect.left + rect.width / 2 - innerWidth / 2) <= 0.5 && Math.abs(rect.top + rect.height / 2 - innerHeight / 2) <= 0.5; })()")) {
        throw "Delete Collection confirmation is not centered in the browser viewport."
    }
    Eval-Js $socket "(() => { const cancel = [...document.querySelectorAll('.delete-collection-dialog button')].find((node) => node.textContent.trim() === 'Cancel'); if (!cancel) return false; cancel.click(); return true; })()" | Out-Null
    Wait-ForJs $socket "document.querySelector('.delete-collection-dialog') === null" 3000 | Out-Null

    Eval-Js $socket "(() => { const row = Array.from(document.querySelectorAll('.nav-row')).find((node) => node.textContent.includes('$ArtistCollection')); if (!row) return false; row.click(); return true; })()" | Out-Null
    if (-not (Wait-ForJs $socket "document.querySelectorAll('.artists-pane .workspace-row').length > 0" 8000)) {
        throw "$ArtistCollection artists did not render."
    }
    Start-Sleep -Milliseconds 500
    $artist = Eval-Js $socket $metricsJs
    $artistPath = Join-Path $OutputDir "musiclib-artist-collection.png"
    Save-Screenshot $socket $artistPath

    if (-not (Eval-Js $socket "(() => { const rows = [...document.querySelectorAll('.artists-pane .workspace-row')]; const row = '$ArtistName' ? rows.find((node) => node.textContent.includes('$ArtistName')) : rows[0]; if (!row) return false; row.click(); return true; })()")) {
        throw "Artist '$ArtistName' was not visible in $ArtistCollection."
    }
    if (-not (Wait-ForJs $socket "(() => { const meta = document.querySelector('.albums-pane .pane-header__meta'); if (!meta || !meta.textContent.trim()) return false; return !!(document.querySelector('.albums-pane .pane-loading') || document.querySelector('.albums-pane .workspace-grid') || Array.from(document.querySelectorAll('.albums-pane .pane-empty')).find((node) => !node.textContent.includes('Select an artist'))); })()" 3000)) {
        $albumPaneState = Eval-Js $socket "(() => JSON.stringify({ selected: document.querySelector('.artists-pane .workspace-row.is-selected')?.textContent.trim() ?? null, meta: document.querySelector('.albums-pane .pane-header__meta')?.textContent.trim() ?? null, empty: document.querySelector('.albums-pane .pane-empty')?.textContent.trim() ?? null, loading: !!document.querySelector('.albums-pane .pane-loading') }))()"
        throw "$ArtistCollection album pane did not render after selecting an artist. Rendered state: $albumPaneState"
    }
    Start-Sleep -Milliseconds 500
    if (-not (Eval-Js $socket "(() => [...document.querySelectorAll('.albums-pane .workspace-row')].every((row) => { const checkbox = row.querySelector('input[type=checkbox]'); if (!checkbox || checkbox.disabled) return true; return checkbox.checked ? !!row.querySelector('.album-presence-text--nonlocal-checked') : !!row.querySelector('.album-presence-text--nonlocal-unchecked'); }))()")) {
        throw "$ArtistCollection has a non-local album with incorrect checked or unchecked styling."
    }
    $album = Eval-Js $socket $metricsJs
    $albumPath = Join-Path $OutputDir "musiclib-album-selection.png"
    Save-Screenshot $socket $albumPath

    Eval-Js $socket "(() => { const row = Array.from(document.querySelectorAll('.nav-row')).find((node) => node.textContent.includes('$TitleCollection')); if (!row) return false; row.click(); return true; })()" | Out-Null
    if (-not (Wait-ForJs $socket "document.querySelectorAll('.titles-pane .workspace-row').length > 0" 8000)) {
        throw "$TitleCollection titles did not render."
    }
    $titleGridState = Eval-Js $socket "(() => { const headers = [...document.querySelectorAll('.titles-pane .workspace-grid__header-cell')]; return JSON.stringify({ count: headers.length, labels: headers.map((node) => node.textContent.trim()), yearResizable: !!headers[2]?.querySelector('.column-resize-handle'), action: !!document.querySelector('.titles-pane .row-action-cell'), select: !!document.querySelector('.titles-pane .v-select') }); })()"
    if (-not (Eval-Js $socket "(() => { const headers = [...document.querySelectorAll('.titles-pane .workspace-grid__header-cell')]; return headers.length === 4 && headers.slice(0, 3).map((node) => node.textContent.trim()).join('|') === 'Title|Artist|Year' && headers[3].textContent.trim() === '' && !!headers[2].querySelector('.column-resize-handle') && !document.querySelector('.titles-pane .row-action-cell') && !document.querySelector('.titles-pane .v-select'); })()")) {
        throw "$TitleCollection title grid must contain Title, Artist, and resizable Year columns followed by an empty flexible spacer. Rendered state: $titleGridState"
    }
    Start-Sleep -Milliseconds 500
    $title = Eval-Js $socket $metricsJs
    $titlePath = Join-Path $OutputDir "musiclib-title-collection.png"
    Save-Screenshot $socket $titlePath

    Eval-Js $socket "(() => { const tab = [...document.querySelectorAll('.app-tabs .v-tab')].find((node) => node.textContent.includes('Artists')); if (!tab) return false; tab.click(); return true; })()" | Out-Null
    if (-not (Wait-ForJs $socket "!!document.querySelector('.artists-page') && (!!document.querySelector('.artists-table-pane .pane-loading') || !!document.querySelector('.artists-screen-grid'))" 3000)) {
        throw "Artists page did not render its pane or initial pane spinner."
    }
    if (-not (Wait-ForJs $socket "document.querySelectorAll('.artists-screen-grid .workspace-row').length > 0" 60000)) {
        throw "Artists page did not finish its initial multi-row load."
    }
    if ($CheckProviderDialog) {
        if (-not (Eval-Js $socket "(() => { const button = [...document.querySelectorAll('.artists-screen-grid .workspace-row .mdi-link-plus')].map((icon) => icon.closest('button')).find((candidate) => candidate && !candidate.disabled); if (!button) return false; button.click(); return true; })()")) {
            throw "Provider matching dialog could not be opened."
        }
        if (-not (Wait-ForJs $socket "document.querySelector('.provider-match-dialog') !== null" 3000)) {
            throw "Provider matching dialog did not render."
        }
        Start-Sleep -Milliseconds 500
        $providerDialogState = Eval-Js $socket "(() => { const card = document.querySelector('.provider-match-dialog'); const content = card.closest('.v-overlay__content'); const rect = card.getBoundingClientRect(); return JSON.stringify({ width: rect.width, height: rect.height, contentWidth: content.getBoundingClientRect().width, expectedWidth: Math.min(innerWidth * 0.55, 1500), expectedHeight: innerHeight * 0.75, centerOffsetX: Math.abs(rect.left + rect.width / 2 - innerWidth / 2), centerOffsetY: Math.abs(rect.top + rect.height / 2 - innerHeight / 2), overflowX: getComputedStyle(card).overflowX }); })()"
        $providerDialog = $providerDialogState | ConvertFrom-Json
        if ($providerDialog.centerOffsetX -gt 0.5 -or $providerDialog.centerOffsetY -gt 0.5 -or [Math]::Abs($providerDialog.width - $providerDialog.expectedWidth) -gt 0.5 -or [Math]::Abs($providerDialog.height - $providerDialog.expectedHeight) -gt 0.5 -or $providerDialog.overflowX -ne "hidden") {
            throw "Provider matching dialog is not centered at the shared large-dialog size: $providerDialogState"
        }
        Eval-Js $socket "(() => { const close = [...document.querySelectorAll('.provider-match-dialog button')].find((node) => node.textContent.trim() === 'Close'); if (!close) return false; close.click(); return true; })()" | Out-Null
        Wait-ForJs $socket "document.querySelector('.provider-match-dialog') === null" 3000 | Out-Null
    }
    $singleActionState = Eval-Js $socket @'
(() => {
  const row = [...document.querySelectorAll('.artists-screen-grid .workspace-row')]
    .find((candidate) => candidate.querySelectorAll('.row-actions button').length === 1);
  if (!row) return JSON.stringify({ found: false });
  const cell = row.querySelector('.row-action-cell');
  const button = row.querySelector('.row-actions button');
  const clone = button.cloneNode(true);
  clone.classList.remove('action-button--icon-only');
  clone.classList.add('action-button--labeled');
  const label = document.createElement('span');
  label.textContent = 'Add providers';
  clone.querySelector('.v-btn__content').append(label);
  Object.assign(clone.style, { position: 'fixed', visibility: 'hidden', width: 'max-content' });
  document.body.append(clone);
  const cellStyle = getComputedStyle(cell);
  const available = cell.clientWidth - parseFloat(cellStyle.paddingLeft) - parseFloat(cellStyle.paddingRight);
  const required = clone.getBoundingClientRect().width;
  clone.remove();
  return JSON.stringify({
    found: true,
    available,
    required,
    label: button.textContent.trim()
  });
})()
'@
    $singleAction = $singleActionState | ConvertFrom-Json
    if ($singleAction.found -and $singleAction.available -ge $singleAction.required -and $singleAction.label -ne "Add providers") {
        throw "A lone Add providers action collapsed despite fitting its action cell: $singleActionState"
    }
    $artistsPath = Join-Path $OutputDir "musiclib-artists-page.png"
    Save-Screenshot $socket $artistsPath

    Eval-Js $socket "(() => { const tab = [...document.querySelectorAll('.app-tabs .v-tab')].find((node) => node.textContent.includes('Settings')); if (!tab) return false; tab.click(); return true; })()" | Out-Null
    if (-not (Wait-ForJs $socket "!!document.querySelector('.settings-page')" 3000)) {
        throw "Settings page did not render before the Artists cache revisit check."
    }
    Eval-Js $socket "(() => { const tab = [...document.querySelectorAll('.app-tabs .v-tab')].find((node) => node.textContent.includes('Artists')); if (!tab) return false; tab.click(); return true; })()" | Out-Null
    if (-not (Wait-ForJs $socket "document.querySelectorAll('.artists-screen-grid .workspace-row').length > 0" 3000)) {
        throw "Artists page did not render from its session cache after returning from Settings."
    }

    $conflictOpened = Eval-Js $socket "(() => { const button = document.querySelector('.artists-screen-grid .workspace-row .mdi-alert-outline')?.closest('button'); if (!button) return false; button.click(); return true; })()"
    if ($conflictOpened -and (Wait-ForJs $socket "!!document.querySelector('.provider-conflict-dialog .provider-conflict-choice')" 10000)) {
        Start-Sleep -Milliseconds 500
        if (-not (Eval-Js $socket "(() => { const dialog = document.querySelector('.provider-conflict-dialog'); const rect = dialog.getBoundingClientRect(); return Math.abs(rect.left + rect.width / 2 - innerWidth / 2) <= 0.5 && Math.abs(rect.top + rect.height / 2 - innerHeight / 2) <= 0.5 && Math.abs(rect.width - Math.min(innerWidth * 0.55, 1500)) <= 0.5 && Math.abs(rect.height - innerHeight * 0.75) <= 0.5 && getComputedStyle(dialog.querySelector('.provider-conflict-dialog__body')).overflowX === 'hidden'; })()")) {
            throw "Provider conflict dialog is not centered at the shared large-dialog size."
        }
        $choiceOpacity = Eval-Js $socket "(() => [...document.querySelectorAll('.provider-conflict-dialog .provider-conflict-choice')].every((node) => getComputedStyle(node).opacity === '1'))()"
        if (-not $choiceOpacity) {
            throw "Provider conflict choices must remain fully opaque while the conflict section is highlighted."
        }
        $beforeHover = Eval-Js $socket "(() => JSON.stringify([...document.querySelectorAll('.provider-conflict-album-group--open .provider-conflict-choice')].map((node) => getComputedStyle(node).backgroundColor)))()"
        Send-Cdp $socket "DOM.enable" | Out-Null
        Send-Cdp $socket "CSS.enable" | Out-Null
        $document = Send-Cdp $socket "DOM.getDocument" @{ depth = 0 }
        $group = Send-Cdp $socket "DOM.querySelector" @{ nodeId = $document.result.root.nodeId; selector = ".provider-conflict-album-group--open" }
        Send-Cdp $socket "CSS.forcePseudoState" @{ nodeId = $group.result.nodeId; forcedPseudoClasses = @("hover") } | Out-Null
        $afterHover = Eval-Js $socket "(() => JSON.stringify([...document.querySelectorAll('.provider-conflict-album-group--open .provider-conflict-choice')].map((node) => getComputedStyle(node).backgroundColor)))()"
        if ($beforeHover -ne $afterHover) {
            throw "Provider conflict section hover changed choice-tile background colors: $beforeHover -> $afterHover"
        }
    }

    @{
        appUrl = $AppUrl
        screenshots = @{
            initial = $initialPath
            collectionEdit = $collectionEditPath
            artist = $artistPath
            album = $albumPath
            title = $titlePath
            artists = $artistsPath
        }
        initial = $initial
        collectionEdit = $collectionEdit
        artist = $artist
        album = $album
        title = $title
    } | ConvertTo-Json -Depth 30
} finally {
    if ($socket) {
        $socket.Dispose()
    }
    if ($startedBrowser -and -not $KeepChromeOpen) {
        Stop-CdpBrowser
    }
}
