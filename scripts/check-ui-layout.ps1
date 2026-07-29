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
$layoutPreferenceBackup = @{}
foreach ($key in @(
    "collections-screen.artist-layout.panes",
    "collections-screen.title-layout.panes",
    "collections-screen.titles-pane.title",
    "artists-screen.artists-pane.name"
)) {
    $url = "$($AppUrl.TrimEnd('/'))/api/preferences/$([Uri]::EscapeDataString($key))"
    try {
        $preference = Invoke-RestMethod -Uri $url -Method Get
        $layoutPreferenceBackup[$key] = @{ exists = $true; value = $preference.value }
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 404) {
            throw
        }
        $layoutPreferenceBackup[$key] = @{ exists = $false; value = $null }
    }
}

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

    $collectionActionState = Eval-Js $socket @'
(async () => {
  const pane = document.querySelector('.collections-pane');
  const resizer = pane?.nextElementSibling;
  const row = document.querySelector('.nav-row.is-selected') ?? document.querySelector('.nav-row');
  const actions = row?.querySelector('.adaptive-row-actions');
  if (!pane || !resizer?.classList.contains('pane-resizer') || !row || !actions) return JSON.stringify({ found: false });
  row.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true }));
  const pause = () => new Promise((resolve) => setTimeout(resolve, 200));
  const originalWidth = pane.getBoundingClientRect().width;
  const preferenceUrl = '/api/preferences/' + encodeURIComponent('collections-screen.title-layout.panes');
  const preferenceResponse = await fetch(preferenceUrl);
  const originalPreference = preferenceResponse.ok ? (await preferenceResponse.json()).value : null;
  const restorePreference = () => fetch(preferenceUrl, originalPreference === null
    ? { method: 'DELETE' }
    : { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ value: originalPreference }) });
  const dragTo = async (width) => {
    const startX = resizer.getBoundingClientRect().left + resizer.getBoundingClientRect().width / 2;
    const targetX = pane.getBoundingClientRect().left + width;
    resizer.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, button: 0, clientX: startX, pointerId: 1, isPrimary: true }));
    window.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, buttons: 1, clientX: targetX, pointerId: 1, isPrimary: true }));
    window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, button: 0, clientX: targetX, pointerId: 1, isPrimary: true }));
    await pause();
  };
  const state = () => {
    const controls = [...actions.querySelectorAll('[data-adaptive-control]')];
    const values = [...new Set(controls.map((control) => control.dataset.showLabel))];
    return {
      labels: values[0] === 'true',
      mixed: values.length > 1,
      overflow: actions.getBoundingClientRect().right - row.getBoundingClientRect().right,
      controls: controls.length
    };
  };
  let result;
  try {
    const states = [state()];
    await dragTo(250);
    states.push(state());
    result = {
      found: true,
      controls: states[0].controls,
      labeled: states.some((entry) => entry.labels),
      collapsed: states.some((entry) => !entry.labels),
      mixed: states.some((entry) => entry.mixed),
      overflow: Math.max(...states.map((entry) => entry.overflow))
    };
  } finally {
    await dragTo(originalWidth);
    await new Promise((resolve) => setTimeout(resolve, 1000));
    await restorePreference();
  }
  return JSON.stringify(result);
})()
'@
    $collectionAction = $collectionActionState | ConvertFrom-Json
    if (-not $collectionAction.found -or $collectionAction.controls -ne 3 -or -not $collectionAction.labeled -or -not $collectionAction.collapsed -or $collectionAction.mixed -or $collectionAction.overflow -gt 0.5) {
        throw "Collection navigation actions did not switch cleanly as one complete action set: $collectionActionState"
    }

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
        Eval-Js $socket "(() => { const scrim = document.querySelector('.scan-report-dialog-content')?.closest('.v-overlay')?.querySelector('.v-overlay__scrim'); if (!scrim) return false; scrim.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); scrim.click(); return true; })()" | Out-Null
        if (-not (Wait-ForJs $socket "document.querySelector('.scan-report-dialog-content') === null" 3000)) {
            throw "Report dialog did not close through its backdrop."
        }
        Eval-Js $socket "(() => { const scrim = document.querySelector('.status-history-dialog')?.closest('.v-overlay')?.querySelector('.v-overlay__scrim'); if (!scrim) return false; scrim.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); scrim.click(); return true; })()" | Out-Null
        if (-not (Wait-ForJs $socket "document.querySelector('.status-history-dialog') === null" 3000)) {
            throw "Status history did not close through its backdrop."
        }
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
    $collectionArtistActionState = Eval-Js $socket @'
(async () => {
  const pane = document.querySelector('.artists-pane');
  const resizer = pane?.nextElementSibling;
  const rows = [...document.querySelectorAll('.artists-pane .workspace-row')]
    .filter((row) => row.querySelector('.artist-issue-chip'));
  const row = rows.sort((left, right) =>
    right.querySelector('.cell-strong').scrollWidth - left.querySelector('.cell-strong').scrollWidth
  )[0];
  const actions = row?.querySelector('.adaptive-row-actions');
  const issue = row?.querySelector('.artist-issue-chip');
  if (!pane || !resizer?.classList.contains('pane-resizer') || !row || !actions || !issue) return JSON.stringify({ found: false });
  row.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true }));
  const pause = () => new Promise((resolve) => setTimeout(resolve, 200));
  const originalWidth = pane.getBoundingClientRect().width;
  const preferenceUrl = '/api/preferences/' + encodeURIComponent('collections-screen.artist-layout.panes');
  const preferenceResponse = await fetch(preferenceUrl);
  const originalPreference = preferenceResponse.ok ? (await preferenceResponse.json()).value : null;
  const restorePreference = () => fetch(preferenceUrl, originalPreference === null
    ? { method: 'DELETE' }
    : { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ value: originalPreference }) });
  const dragTo = async (width) => {
    const startX = resizer.getBoundingClientRect().left + resizer.getBoundingClientRect().width / 2;
    const targetX = pane.getBoundingClientRect().left + width;
    resizer.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, button: 0, clientX: startX, pointerId: 1, isPrimary: true }));
    window.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, buttons: 1, clientX: targetX, pointerId: 1, isPrimary: true }));
    window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, button: 0, clientX: targetX, pointerId: 1, isPrimary: true }));
    await pause();
  };
  const state = () => {
    const controls = [...actions.querySelectorAll('[data-adaptive-control]')];
    const values = [...new Set(controls.map((control) => control.dataset.showLabel))];
    return {
      actionLabels: values[0] === 'true',
      issueLabel: issue.dataset.showLabel === 'true',
      mixed: values.length > 1,
      overflow: actions.getBoundingClientRect().right - row.getBoundingClientRect().right
    };
  };
  let result;
  try {
    const states = [state()];
    await dragTo(280);
    states.push(state());
    result = {
      found: true,
      labeled: states.some((entry) => entry.actionLabels),
      collapsed: states.some((entry) => !entry.actionLabels),
      ordering: states.every((entry) => entry.issueLabel || !entry.actionLabels),
      issueMetrics: (() => {
        const style = getComputedStyle(issue);
        const labelStyle = getComputedStyle(issue.querySelector('.artist-issue-chip__label'));
        const underlay = issue.querySelector('.v-chip__underlay');
        const underlayStyle = getComputedStyle(underlay);
        const colorProbe = document.createElement('span');
        colorProbe.style.color = getComputedStyle(document.documentElement).getPropertyValue('--app-text-attention-muted').trim();
        document.body.append(colorProbe);
        const attentionColor = getComputedStyle(colorProbe).color;
        colorProbe.remove();
        const metrics = {
          height: issue.getBoundingClientRect().height,
          underlayHeight: underlay.getBoundingClientRect().height,
          fontSize: parseFloat(style.fontSize),
          labelFontSize: parseFloat(labelStyle.fontSize),
          fontWeight: Number(style.fontWeight),
          color: style.color,
          attentionColor,
          underlayColor: underlayStyle.backgroundColor,
          underlayOpacity: parseFloat(underlayStyle.opacity)
        };
        return {
          ...metrics,
          valid: Math.abs(metrics.height - 22) <= 0.5
          && Math.abs(underlay.getBoundingClientRect().height - 22) <= 0.5
          && metrics.fontSize === 14
          && metrics.labelFontSize === 12
          && metrics.fontWeight === 400
          && style.fontVariantNumeric.includes('tabular-nums')
          && metrics.color === attentionColor
          && metrics.underlayColor === attentionColor
          && metrics.underlayOpacity === 0.12
          && issue.scrollWidth <= issue.clientWidth
        };
      })(),
      mixed: states.some((entry) => entry.mixed),
      overflow: Math.max(...states.map((entry) => entry.overflow))
    };
  } finally {
    await dragTo(originalWidth);
    await new Promise((resolve) => setTimeout(resolve, 1000));
    await restorePreference();
  }
  result.issueValid = result.issueMetrics.valid;
  return JSON.stringify(result);
})()
'@
    $collectionArtistAction = $collectionArtistActionState | ConvertFrom-Json
    if (-not $collectionArtistAction.found -or -not $collectionArtistAction.labeled -or -not $collectionArtistAction.ordering -or -not $collectionArtistAction.issueValid -or $collectionArtistAction.mixed -or $collectionArtistAction.overflow -gt 0.5) {
        throw "Collection artist actions and unchecked chips did not follow the shared collapse order: $collectionArtistActionState"
    }
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
    $albumActionState = Eval-Js $socket @'
(async () => {
  const pane = document.querySelector('.albums-pane');
  const resizer = pane?.previousElementSibling;
  const row = [...document.querySelectorAll('.albums-pane .workspace-row')]
    .find((candidate) => candidate.querySelector('.album-info-button'));
  if (!pane || !resizer?.classList.contains('pane-resizer') || !row) return JSON.stringify({ found: false });
  const pause = () => new Promise((resolve) => setTimeout(resolve, 200));
  const originalWidth = pane.getBoundingClientRect().width;
  const preferenceUrl = '/api/preferences/' + encodeURIComponent('collections-screen.artist-layout.panes');
  const preferenceResponse = await fetch(preferenceUrl);
  const originalPreference = preferenceResponse.ok ? (await preferenceResponse.json()).value : null;
  const restorePreference = () => fetch(preferenceUrl, originalPreference === null
    ? { method: 'DELETE' }
    : { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ value: originalPreference }) });
  const dragTo = async (width) => {
    const startX = resizer.getBoundingClientRect().left + resizer.getBoundingClientRect().width / 2;
    const targetX = pane.getBoundingClientRect().right - width;
    resizer.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, button: 0, clientX: startX, pointerId: 1, isPrimary: true }));
    window.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, buttons: 1, clientX: targetX, pointerId: 1, isPrimary: true }));
    window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, button: 0, clientX: targetX, pointerId: 1, isPrimary: true }));
    await pause();
  };
  const state = () => {
    const actions = row.querySelector('.adaptive-row-actions');
    const controls = [...actions.querySelectorAll('[data-adaptive-control]')];
    const values = [...new Set(controls.map((control) => control.dataset.showLabel))];
    const rect = actions.getBoundingClientRect();
    const parent = actions.parentElement.getBoundingClientRect();
    return {
      labels: values[0] === 'true',
      mixed: values.length > 1,
      overflow: rect.right - parent.right,
      controls: controls.length
    };
  };
  let result;
  try {
    const states = [state()];
    await dragTo(620);
    states.push(state());
    result = {
      found: true,
      controls: states[0].controls,
      labeled: states.some((entry) => entry.labels),
      collapsed: states.some((entry) => !entry.labels),
      mixed: states.some((entry) => entry.mixed),
      overflow: Math.max(...states.map((entry) => entry.overflow))
    };
  } finally {
    await dragTo(originalWidth);
    await new Promise((resolve) => setTimeout(resolve, 1000));
    await restorePreference();
  }
  return JSON.stringify(result);
})()
'@
    $albumAction = $albumActionState | ConvertFrom-Json
    if (-not $albumAction.found -or $albumAction.controls -ne 1 -or -not $albumAction.labeled -or -not $albumAction.collapsed -or $albumAction.mixed -or $albumAction.overflow -gt 0.5) {
        throw "Album action column did not switch cleanly between complete labeled and icon-only states: $albumActionState"
    }
    $collectionChipState = Eval-Js $socket "(() => { const chips = [...document.querySelectorAll('.album-collection-chips .collection-chip')]; const metrics = chips.map((chip) => { const style = getComputedStyle(chip); const underlay = chip.querySelector('.v-chip__underlay'); return { height: chip.getBoundingClientRect().height, underlayHeight: underlay.getBoundingClientRect().height, fontSize: parseFloat(style.fontSize), fontWeight: Number(style.fontWeight) }; }); return JSON.stringify({ count: chips.length, valid: metrics.every((chip) => Math.abs(chip.height - 22) <= 0.5 && Math.abs(chip.underlayHeight - 22) <= 0.5 && chip.fontSize === 12 && chip.fontWeight === 400), metrics }); })()"
    $collectionChips = $collectionChipState | ConvertFrom-Json
    if ($collectionChips.count -eq 0 -or -not $collectionChips.valid) {
        throw "Collection chips do not match the shared literal 22px/12px contract: $collectionChipState"
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
    $yearChipState = Eval-Js $socket "(() => { const chips = [...document.querySelectorAll('.titles-pane [data-column=""title.releaseYear""] .numeric-chip--year')]; return JSON.stringify({ count: chips.length, valid: chips.every((chip) => { const style = getComputedStyle(chip); const cell = getComputedStyle(chip.closest('.workspace-grid__cell')); const underlay = chip.querySelector('.v-chip__underlay'); return Math.abs(chip.getBoundingClientRect().height - 26) <= 0.5 && Math.abs(underlay.getBoundingClientRect().height - 26) <= 0.5 && parseFloat(style.fontSize) === 16 && Number(style.fontWeight) === 600 && style.fontVariantNumeric.includes('tabular-nums') && chip.scrollWidth <= chip.clientWidth && cell.justifyContent === 'center'; }) }); })()"
    $yearChips = $yearChipState | ConvertFrom-Json
    if ($yearChips.count -eq 0 -or -not $yearChips.valid) {
        throw "Year chips do not match the shared literal 26px/16px/600 contract: $yearChipState"
    }
    $titleMinimumState = Eval-Js $socket @'
(async () => {
  const cell = document.querySelector('.titles-pane [data-column="title.title"].workspace-grid__header-cell');
  const handle = cell?.querySelector('.column-resize-handle');
  if (!cell || !handle) return JSON.stringify({ found: false });
  const originalWidth = cell.getBoundingClientRect().width;
  const pause = () => new Promise((resolve) => setTimeout(resolve, 200));
  const dragTo = async (width) => {
    const startX = handle.getBoundingClientRect().left + handle.getBoundingClientRect().width / 2;
    const targetX = startX + width - cell.getBoundingClientRect().width;
    handle.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, button: 0, clientX: startX, pointerId: 1, isPrimary: true }));
    window.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, buttons: 1, clientX: targetX, pointerId: 1, isPrimary: true }));
    window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, button: 0, clientX: targetX, pointerId: 1, isPrimary: true }));
    await pause();
  };
  let minimumWidth;
  try {
    await dragTo(10);
    minimumWidth = cell.getBoundingClientRect().width;
  } finally {
    await dragTo(originalWidth);
  }
  return JSON.stringify({ found: true, minimumWidth, originalWidth, restoredWidth: cell.getBoundingClientRect().width });
})()
'@
    $titleMinimum = $titleMinimumState | ConvertFrom-Json
    if (-not $titleMinimum.found -or $titleMinimum.minimumWidth -lt 96 -or $titleMinimum.minimumWidth -gt 96.5 -or [Math]::Abs($titleMinimum.restoredWidth - $titleMinimum.originalWidth) -gt 0.5) {
        throw "The compound Title header did not preserve its 96px minimum and restore its prior width: $titleMinimumState"
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
    if (-not (Eval-Js $socket "(() => { const row = [...document.querySelectorAll('.artists-screen-grid .workspace-row')].find((node) => node.textContent.includes('Across the Rain')); if (!row) return false; row.click(); return true; })()")) {
        throw "The Artists page year-chip fixture was not visible."
    }
    if (-not (Wait-ForJs $socket "document.querySelector('.artist-known-album__year .numeric-chip--year') !== null" 5000)) {
        throw "The selected artist did not render a shared Known Albums year chip."
    }
$artistColumnState = Eval-Js $socket @'
(async () => {
  const rows = [...document.querySelectorAll('.artists-screen-grid .workspace-row')];
  const countChips = [...document.querySelectorAll('.artists-screen-grid .numeric-chip--count')];
  const countValid = countChips.every((chip) => {
    const style = getComputedStyle(chip);
    const cell = getComputedStyle(chip.closest('.artists-count-cell'));
    const underlay = chip.querySelector('.v-chip__underlay');
    return Math.abs(chip.getBoundingClientRect().height - 24) <= 0.5
      && Math.abs(underlay.getBoundingClientRect().height - 24) <= 0.5
      && parseFloat(style.fontSize) === 14
      && Number(style.fontWeight) === 400
      && style.fontVariantNumeric.includes('tabular-nums')
      && chip.scrollWidth <= chip.clientWidth
      && cell.justifyContent === 'flex-end';
  });
  const knownYearChips = [...document.querySelectorAll('.artist-known-album__year .numeric-chip--year')];
  const knownYearsUseSharedChips = knownYearChips.length > 0 && knownYearChips.every((chip) => {
    const style = getComputedStyle(chip);
    const underlay = chip.querySelector('.v-chip__underlay');
    return Math.abs(chip.getBoundingClientRect().height - 26) <= 0.5
      && Math.abs(underlay.getBoundingClientRect().height - 26) <= 0.5
      && parseFloat(style.fontSize) === 16
      && Number(style.fontWeight) === 600;
  });
  const colorProbe = document.createElement('span');
  colorProbe.style.color = getComputedStyle(document.documentElement).getPropertyValue('--app-text-attention-muted').trim();
  document.body.append(colorProbe);
  const attentionColor = getComputedStyle(colorProbe).color;
  colorProbe.remove();
  const positiveUncheckedChips = [...document.querySelectorAll('.artists-screen-grid [data-column="artists.unchecked"] .unchecked-count-chip')];
  const uncheckedColorValid = positiveUncheckedChips.length > 0 && positiveUncheckedChips.every((chip) => {
    const style = getComputedStyle(chip);
    const underlayStyle = getComputedStyle(chip.querySelector('.v-chip__underlay'));
    return style.color === attentionColor
      && underlayStyle.backgroundColor === attentionColor
      && parseFloat(underlayStyle.opacity) === 0.12;
  });
  const blankMetadata = rows.some((row) =>
    !row.querySelector('[data-column="artists.country"]').textContent.trim()
    && !row.querySelector('[data-column="artists.status"]').textContent.trim()
    && !row.querySelector('[data-column="artists.provider"]').textContent.trim()
  );
  const providerControlsFit = [...document.querySelectorAll('.artists-provider-list')]
    .every((list) => list.scrollWidth <= list.clientWidth);
  const ellipsisStateMatches = [...document.querySelectorAll('.ellipsized-text')]
    .every((text) => (text.dataset.ellipsized === 'true') === (text.scrollWidth > text.clientWidth));
  const adaptiveSetsAreUnified = [...document.querySelectorAll('.adaptive-row-actions')]
    .every((actions) => new Set([...actions.querySelectorAll('[data-adaptive-control]')].map((control) => control.dataset.showLabel)).size <= 1);
  return JSON.stringify({
    rows: rows.length,
    countChips: countChips.length,
    countValid,
    knownYearsUseSharedChips,
    uncheckedColorValid,
    blankMetadata,
    providerControlsFit,
    ellipsisStateMatches,
    adaptiveSetsAreUnified
  });
})()
'@
    $artistColumns = $artistColumnState | ConvertFrom-Json
    if ($artistColumns.countChips -eq 0 -or -not $artistColumns.countValid -or -not $artistColumns.knownYearsUseSharedChips -or -not $artistColumns.uncheckedColorValid -or -not $artistColumns.blankMetadata -or -not $artistColumns.providerControlsFit -or -not $artistColumns.ellipsisStateMatches -or -not $artistColumns.adaptiveSetsAreUnified) {
        throw "Artists table column contracts are inconsistent: $artistColumnState"
    }
    if (-not (Eval-Js $socket "(() => { const row = [...document.querySelectorAll('.artists-screen-grid .workspace-row')].find((node) => node.textContent.includes('Antti Martikainen')); const button = row?.querySelector('.mdi-alert-outline')?.closest('button'); if (!button) return false; button.click(); return true; })()")) {
        throw "The release-year conflict fixture was not visible."
    }
    if (-not (Wait-ForJs $socket "document.querySelector('.provider-conflict-dialog') !== null" 5000)) {
        throw "The provider conflict dialog did not render."
    }
    if (-not (Eval-Js $socket "(() => { const header = [...document.querySelectorAll('.provider-conflict-album-group__header')].find((node) => node.textContent.includes('Year variant')); if (!header) return false; header.click(); return true; })()")) {
        throw "The provider conflict dialog did not contain a release-year section."
    }
    if (-not (Wait-ForJs $socket "document.querySelector('.provider-conflict-album-group--open .numeric-chip--year') !== null" 3000)) {
        throw "Release-year conflict choices did not use the shared year chip."
    }
    Eval-Js $socket "(() => { const close = [...document.querySelectorAll('.provider-conflict-dialog button')].find((node) => node.textContent.trim() === 'Close'); if (!close) return false; close.click(); return true; })()" | Out-Null
    Wait-ForJs $socket "document.querySelector('.provider-conflict-dialog') === null" 3000 | Out-Null
    if (-not (Wait-ForJs $socket "document.querySelector('.artist-known-album__title-chip') !== null" 5000)) {
        throw "The selected conflict fixture did not render a conflicted title chip."
    }
    $sharedChipState = Eval-Js $socket @'
(() => {
  const conflictTitles = [...document.querySelectorAll('.artist-known-album__title-chip')];
  const metadataStateChips = [...document.querySelectorAll('.album-metadata-chip--warning, .album-metadata-chip--kept-local')];
  const providerChips = [...document.querySelectorAll('.provider-chip')];
  const textMetrics = (chip) => {
    const style = getComputedStyle(chip);
    return {
      color: style.color,
      fontSize: style.fontSize,
      fontStyle: style.fontStyle,
      fontWeight: style.fontWeight
    };
  };
  const stateChipValid = (chip) => {
    const warning = chip.classList.contains('album-metadata-chip--warning');
    const keptLocal = chip.classList.contains('album-metadata-chip--kept-local');
    const style = getComputedStyle(chip);
    const underlay = chip.querySelector('.v-chip__underlay');
    const stateText = textMetrics(chip);
    const stateFill = {
      backgroundColor: style.backgroundColor,
      underlayOpacity: getComputedStyle(underlay).opacity
    };
    const stateBoxShadow = style.boxShadow;
    chip.classList.remove('album-metadata-chip--warning', 'album-metadata-chip--kept-local', 'album-metadata-chip--action');
    const baseText = textMetrics(chip);
    const baseFill = {
      backgroundColor: getComputedStyle(chip).backgroundColor,
      underlayOpacity: getComputedStyle(underlay).opacity
    };
    chip.classList.toggle('album-metadata-chip--warning', warning);
    chip.classList.toggle('album-metadata-chip--kept-local', keptLocal);
    chip.classList.toggle('album-metadata-chip--action', warning);
    const titleChip = chip.classList.contains('artist-known-album__title-chip');
    const fillValid = titleChip
      ? stateFill.backgroundColor === 'rgba(0, 0, 0, 0)' && parseFloat(stateFill.underlayOpacity) === 0
      : JSON.stringify(stateFill) === JSON.stringify(baseFill);
    return Math.abs(chip.getBoundingClientRect().height - 26) <= 0.5
      && Math.abs(underlay.getBoundingClientRect().height - 26) <= 0.5
      && fillValid
      && stateBoxShadow.includes('inset')
      && !stateBoxShadow.includes('), ')
      && JSON.stringify(stateText) === JSON.stringify(baseText);
  };
  const warningChip = metadataStateChips.find((chip) => chip.classList.contains('album-metadata-chip--warning'));
  let simulatedKeptLocalValid = true;
  if (!metadataStateChips.some((chip) => chip.classList.contains('album-metadata-chip--kept-local')) && warningChip) {
    warningChip.classList.remove('album-metadata-chip--warning', 'album-metadata-chip--action');
    warningChip.classList.add('album-metadata-chip--kept-local');
    simulatedKeptLocalValid = stateChipValid(warningChip);
    warningChip.classList.remove('album-metadata-chip--kept-local');
    warningChip.classList.add('album-metadata-chip--warning', 'album-metadata-chip--action');
  }
  return JSON.stringify({
    conflictTitles: conflictTitles.length,
    conflictTitlesValid: conflictTitles.every((chip) => {
      const underlay = chip.querySelector('.v-chip__underlay');
      const badge = chip.closest('.artist-known-album__title-badge');
      return Math.abs(chip.getBoundingClientRect().height - 26) <= 0.5
        && Math.abs(underlay.getBoundingClientRect().height - 26) <= 0.5
        && getComputedStyle(badge).overflow === 'visible';
    }),
    metadataStateChips: metadataStateChips.length,
    metadataStateChipsValid: metadataStateChips.every(stateChipValid) && simulatedKeptLocalValid,
    providerChips: providerChips.length,
    providerChipsValid: providerChips.every((chip) => {
      const style = getComputedStyle(chip);
      return Math.abs(chip.getBoundingClientRect().height - 22) <= 0.5
        && parseFloat(style.fontSize) === 12
        && Number(style.fontWeight) === 800
        && style.borderTopWidth === '0px';
    })
  });
})()
'@
    $sharedChips = $sharedChipState | ConvertFrom-Json
    if ($sharedChips.conflictTitles -eq 0 -or -not $sharedChips.conflictTitlesValid -or $sharedChips.metadataStateChips -eq 0 -or -not $sharedChips.metadataStateChipsValid -or $sharedChips.providerChips -eq 0 -or -not $sharedChips.providerChipsValid) {
        throw "Conflict-state or provider chips do not match their shared literal contracts: $sharedChipState"
    }
    $artistEllipsisState = Eval-Js $socket @'
(async () => {
  const cell = document.querySelector('.artists-screen-grid [data-column="artists.name"].workspace-grid__header-cell');
  const handle = cell?.querySelector('.column-resize-handle');
  if (!cell || !handle) return JSON.stringify({ found: false });
  const originalWidth = cell.getBoundingClientRect().width;
  const pause = () => new Promise((resolve) => setTimeout(resolve, 200));
  const dragTo = async (width) => {
    const startX = handle.getBoundingClientRect().left + handle.getBoundingClientRect().width / 2;
    const targetX = startX + width - cell.getBoundingClientRect().width;
    handle.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, button: 0, clientX: startX, pointerId: 1, isPrimary: true }));
    window.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, buttons: 1, clientX: targetX, pointerId: 1, isPrimary: true }));
    window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, button: 0, clientX: targetX, pointerId: 1, isPrimary: true }));
    await pause();
  };
  let state;
  try {
    await dragTo(10);
    const texts = [...document.querySelectorAll('.artists-screen-grid .ellipsized-text')];
    state = {
      minimumWidth: cell.getBoundingClientRect().width,
      truncated: texts.some((text) => text.dataset.ellipsized === 'true'),
      matches: texts.every((text) => (text.dataset.ellipsized === 'true') === (text.scrollWidth > text.clientWidth))
    };
  } finally {
    await dragTo(originalWidth);
  }
  return JSON.stringify({ found: true, ...state, originalWidth, restoredWidth: cell.getBoundingClientRect().width });
})()
'@
    $artistEllipsis = $artistEllipsisState | ConvertFrom-Json
    if (-not $artistEllipsis.found -or -not $artistEllipsis.truncated -or -not $artistEllipsis.matches -or $artistEllipsis.minimumWidth -lt 62 -or [Math]::Abs($artistEllipsis.restoredWidth - $artistEllipsis.originalWidth) -gt 0.5) {
        throw "Artist text did not expose ellipsis tooltips only while truncated at the shared column minimum: $artistEllipsisState"
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
    $actionResizeState = Eval-Js $socket @'
(async () => {
  const pane = document.querySelector('.artists-table-pane');
  const details = document.querySelector('.artist-details-pane');
  const resizer = pane?.nextElementSibling;
  const rows = [...document.querySelectorAll('.artists-screen-grid .workspace-row')];
  const singleRow = rows.find((row) => row.querySelectorAll('.row-actions button').length === 1);
  const dualRow = rows.find((row) => row.querySelectorAll('.row-actions button').length === 2);
  if (!pane || !details || !resizer?.classList.contains('pane-resizer') || !singleRow || !dualRow) {
    return JSON.stringify({ found: false });
  }
  const originalPaneWidth = pane.getBoundingClientRect().width;
  const pause = () => new Promise((resolve) => setTimeout(resolve, 150));
  const requiredWidth = (row) => {
    const clone = row.querySelector('.row-actions').cloneNode(true);
    clone.querySelectorAll('button').forEach((button) => {
      button.classList.remove('action-button--icon-only');
      button.classList.add('action-button--labeled');
      button.dataset.showLabel = 'true';
    });
    Object.assign(clone.style, { position: 'fixed', visibility: 'hidden', width: 'max-content' });
    document.body.append(clone);
    const width = clone.getBoundingClientRect().width;
    clone.remove();
    return width;
  };
  const measure = (row) => {
    const cell = row.querySelector('.row-action-cell');
    const actions = row.querySelector('.row-actions');
    const cellStyle = getComputedStyle(cell);
    const cellRect = cell.getBoundingClientRect();
    const actionsRect = actions.getBoundingClientRect();
    return {
      available: cellRect.width - parseFloat(cellStyle.paddingLeft) - parseFloat(cellStyle.paddingRight),
      labels: [...actions.querySelectorAll('[data-show-label="true"] .adaptive-control-label')]
        .map((label) => label.textContent.trim())
        .join('|'),
      leftOffset: actionsRect.left - cellRect.left - parseFloat(cellStyle.paddingLeft),
      rightOverflow: actionsRect.right - cellRect.right + parseFloat(cellStyle.paddingRight),
      width: actionsRect.width
    };
  };
  const resizePaneTo = async (target) => {
    const startX = resizer.getBoundingClientRect().left + resizer.getBoundingClientRect().width / 2;
    const delta = target - pane.getBoundingClientRect().width;
    const targetX = startX + delta;
    resizer.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, button: 0, clientX: startX, pointerId: 1, isPrimary: true }));
    window.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, buttons: 1, clientX: targetX, pointerId: 1, isPrimary: true }));
    window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, button: 0, clientX: targetX, pointerId: 1, isPrimary: true }));
    await pause();
  };
  const resizeToAvailable = async (row, target) => {
    for (let attempt = 0; attempt < 3; attempt++) {
      const delta = target - measure(row).available;
      await resizePaneTo(pane.getBoundingClientRect().width + delta);
    }
  };
  let state;
  try {
    const singleRequired = requiredWidth(singleRow);
    const dualRequired = requiredWidth(dualRow);
    await resizeToAvailable(dualRow, Math.ceil(dualRequired) + 1);
    const wideDual = measure(dualRow);
    const wideSingle = measure(singleRow);
    await resizeToAvailable(dualRow, Math.floor(dualRequired) - 2);
    const betweenDual = measure(dualRow);
    const betweenSingle = measure(singleRow);
    await resizeToAvailable(singleRow, Math.floor(singleRequired) - 2);
    const narrowDual = measure(dualRow);
    const narrowSingle = measure(singleRow);
    state = {
      found: true,
      singleRequired,
      dualRequired,
      wideDual,
      wideSingle,
      betweenDual,
      betweenSingle,
      narrowDual,
      narrowSingle
    };
  } finally {
    await resizePaneTo(originalPaneWidth);
  }
  return JSON.stringify(state);
})()
'@
    $actionResize = $actionResizeState | ConvertFrom-Json
    if (-not $actionResize.found) {
        throw "Artists action resize fixture did not contain both one-action and two-action rows."
    }
    if ($actionResize.found -and ($actionResize.wideDual.labels -notmatch "Conflicts" -or $actionResize.wideDual.labels -notmatch "Add providers" -or [Math]::Abs($actionResize.wideDual.leftOffset) -gt 0.5 -or $actionResize.wideDual.rightOverflow -gt 0.5)) {
        throw "Artist row actions collapsed while both labels fit: $actionResizeState"
    }
    if ($actionResize.found -and ($actionResize.wideSingle.labels -ne "Add providers" -or $actionResize.betweenSingle.labels -ne "Add providers" -or [Math]::Abs($actionResize.wideSingle.leftOffset) -gt 0.5 -or [Math]::Abs($actionResize.betweenSingle.leftOffset) -gt 0.5 -or $actionResize.wideSingle.rightOverflow -gt 0.5 -or $actionResize.betweenSingle.rightOverflow -gt 0.5)) {
        throw "Lone Add providers action shifted or collapsed while its label fit: $actionResizeState"
    }
    if ($actionResize.found -and (($actionResize.betweenDual.labels -match "Conflicts|Add providers") -or ($actionResize.narrowDual.labels -match "Conflicts|Add providers") -or ($actionResize.narrowSingle.labels -match "Add providers"))) {
        throw "Artist action labels remained visible after their content stopped fitting: $actionResizeState"
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
    Start-Sleep -Milliseconds 500
    foreach ($key in $layoutPreferenceBackup.Keys) {
        $url = "$($AppUrl.TrimEnd('/'))/api/preferences/$([Uri]::EscapeDataString($key))"
        $backup = $layoutPreferenceBackup[$key]
        if ($backup.exists) {
            Invoke-RestMethod -Uri $url -Method Put -ContentType "application/json" -Body (@{ value = $backup.value } | ConvertTo-Json -Compress) | Out-Null
        } else {
            try {
                Invoke-RestMethod -Uri $url -Method Delete | Out-Null
            } catch {
                if ($_.Exception.Response.StatusCode.value__ -ne 404) {
                    throw
                }
            }
        }
    }
}
