param(
    [Parameter(Mandatory=$true)][string]$P4dPath,
    [string]$P4Path='C:\Program Files\Perforce\p4.exe',
    [string]$LabRoot=(Join-Path $PSScriptRoot '..\..\CodeDeck-Capture-Lab'),
    [string]$RunId=('vcs-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
)
# Requires a local Helix Core 2026.1 server binary. Never connects to the user's P4 server.
$ErrorActionPreference='Stop'
if($RunId -notmatch '^[a-zA-Z0-9_-]+$'){throw 'Invalid fixture name'}
$taskRun=[IO.Path]::GetFullPath((Join-Path $LabRoot $RunId))
if(Test-Path -LiteralPath $taskRun){throw 'Fixture already exists; use a new RunId'}
$taskP4d=(Resolve-Path -LiteralPath $P4dPath).Path
$taskProject=Join-Path $taskRun 'project'
$taskServer=Join-Path $taskRun 'p4-server'
$taskP4Root=Join-Path $taskProject 'p4-revert'
$taskGitRoot=Join-Path $taskProject 'git-revert'
New-Item -ItemType Directory -Path $taskServer,$taskP4Root,$taskGitRoot -Force | Out-Null
$taskListener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
$taskListener.Start(); $taskPort=$taskListener.LocalEndpoint.Port; $taskListener.Stop()
$taskAddress='127.0.0.1:'+$taskPort
$taskOldTickets=$env:P4TICKETS
$taskProcess=$null
try {
    & $taskP4d -r $taskServer '-cset dm.user.noautocreate=0'
    if($LASTEXITCODE -ne 0){throw 'Cannot configure isolated P4 server'}
    $taskProcess=Start-Process -FilePath $taskP4d -ArgumentList @('-r',$taskServer,'-p',$taskAddress,'-L',(Join-Path $taskServer 'server.log'),'-J',(Join-Path $taskServer 'journal')) -WorkingDirectory $taskServer -WindowStyle Hidden -PassThru
    $taskReady=$false
    for($taskAttempt=0;$taskAttempt -lt 40;$taskAttempt++) {
        $taskProbe=[Net.Sockets.TcpClient]::new()
        try{$taskProbe.Connect('127.0.0.1',$taskPort);$taskReady=$true;break}
        catch{Start-Sleep -Milliseconds 100}finally{$taskProbe.Dispose()}
    }
    if(-not $taskReady){throw 'Isolated P4 server did not start'}
    $env:P4TICKETS=Join-Path $taskRun 'p4tickets'
    $taskConnection=@('-C','none','-p',$taskAddress,'-u','codedeck-test','-c','codedeck-test')
    $taskClient="Client: codedeck-test`nOwner: codedeck-test`nRoot: $taskP4Root`nLineEnd: unix`nView:`n`t//depot/... //codedeck-test/...`n"
    # Autocreate the disposable user, then bootstrap its password using only this new server root.
    $taskClient | & $P4Path @taskConnection client -i 2>$null
    "CodeDeck-Test-211`nCodeDeck-Test-211" | & $taskP4d -r $taskServer --reset-password codedeck-test
    if($LASTEXITCODE -ne 0){throw 'Cannot bootstrap isolated P4 user (requires p4d 2026.1)'}
    'CodeDeck-Test-211' | & $P4Path @taskConnection login
    if($LASTEXITCODE -ne 0){throw 'Isolated P4 login failed'}
    $taskClient | & $P4Path @taskConnection client -i
    if($LASTEXITCODE -ne 0){throw 'Isolated P4 client creation failed'}
    $taskFiles=@('Source/UI/InteractionUIController.cpp','Source/UI/InteractionUIController.h','Source/Task/EndInteraction.cpp','Source/Task/EndInteraction.h')
    foreach($taskName in $taskFiles) {
        $taskFile=Join-Path $taskP4Root $taskName
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($taskFile)) | Out-Null
        [IO.File]::WriteAllText($taskFile,"repository baseline`n",[Text.UTF8Encoding]::new($false))
        & $P4Path @taskConnection add -t text $taskFile
        if($LASTEXITCODE -ne 0){throw 'Isolated P4 add failed'}
    }
    & $P4Path @taskConnection submit -d 'fixture baseline'
    if($LASTEXITCODE -ne 0){throw 'Isolated P4 submit failed'}
    & $P4Path @taskConnection counter -f change 87332
    if($LASTEXITCODE -ne 0){throw 'Isolated P4 counter failed'}
    "Change: new`nClient: codedeck-test`nUser: codedeck-test`nDescription:`n`tfixture edits`n" | & $P4Path @taskConnection change -i
    if($LASTEXITCODE -ne 0){throw 'Isolated P4 changelist creation failed'}
    foreach($taskName in $taskFiles) {
        & $P4Path @taskConnection edit -c 87333 (Join-Path $taskP4Root $taskName)
        if($LASTEXITCODE -ne 0){throw 'Isolated P4 edit failed'}
    }
    git -C $taskGitRoot init --quiet
    foreach($taskSetting in @(@('user.name','CodeDeck Test'),@('user.email','codedeck-test@example.invalid'),@('core.autocrlf','false'),@('core.fsmonitor','false'),@('commit.gpgsign','false'),@('core.hooksPath',(Join-Path $taskRun 'no-hooks')))) {
        git -C $taskGitRoot config $taskSetting[0] $taskSetting[1]
        if($LASTEXITCODE -ne 0){throw 'Isolated Git configuration failed'}
    }
    foreach($taskVersion in @(@("repository baseline`n",'fixture baseline'),@("before`r`n",'fixture change to revert'))) {
        [IO.File]::WriteAllText((Join-Path $taskGitRoot 'source.cpp'),$taskVersion[0],[Text.UTF8Encoding]::new($false))
        git -C $taskGitRoot add source.cpp
        git -C $taskGitRoot commit --quiet -m $taskVersion[1]
        if($LASTEXITCODE -ne 0){throw 'Isolated Git commit failed'}
    }
    $taskFixture=@{run=$taskRun;project=$taskProject;p4port=$taskAddress;p4pid=$taskProcess.Id;p4d=$taskP4d;tickets=$env:P4TICKETS}
    $taskFixture | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskRun 'fixture.json')
    Write-Output ('Fixture ready: '+(Join-Path $taskRun 'fixture.json'))
} catch {
    if($taskProcess -and -not $taskProcess.HasExited){$taskProcess.Kill()}
    throw
} finally { $env:P4TICKETS=$taskOldTickets }
