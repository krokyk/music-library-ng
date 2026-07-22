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
