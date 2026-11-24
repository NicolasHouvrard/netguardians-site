<#
.SYNOPSIS
  Signe un jar de plugin (Ed25519) puis verifie la signature avec les cles publiques du coeur.

.EXAMPLE
  .\outils\signer.ps1 -Jar mon-plugin-1.2.0.jar -Cle C:\Cles\signature\prod-2026-11-a.prive -KeyId prod-2026-11-a

.NOTES
  A lancer sur le poste de signature. La cle privee est seulement passee (par son CHEMIN) a l'outil Java :
  ce script ne la lit pas, ne l'affiche pas, n'en fait aucune copie. Ecrit <jar>.sig a cote du jar.
  Prerequis : Java 25 ou plus, et le jar du SDK (plugins-sdk\target\site-plugins-sdk-0.0.1.jar).
  Code retour : 0 = signe et verifie ; 1 = erreur (message en francais).
#>
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [Parameter(Mandatory = $true)][string]$Cle,
    [Parameter(Mandatory = $true)][string]$KeyId,
    # Reserve aux tests : autre fichier de cles publiques que celui du coeur.
    [string]$ClesPubliques
)

$ErrorActionPreference = 'Stop'

function Echec([string]$message) {
    [Console]::Error.WriteLine("ERREUR : $message")
    exit 1
}

# Lance un executable sans que PowerShell 5.1 transforme stderr en erreur ; renvoie code + sortie.
function Lancer([string]$exe, [string[]]$arguments) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $exe
    $psi.Arguments = ($arguments | ForEach-Object { '"' + ($_ -replace '"', '\"') + '"' }) -join ' '
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $sortieTask = $p.StandardOutput.ReadToEndAsync()
    $erreurTask = $p.StandardError.ReadToEndAsync()
    $p.WaitForExit()
    [pscustomobject]@{ Code = $p.ExitCode; Sortie = ($sortieTask.Result + $erreurTask.Result).Trim() }
}

function VersionJava([string]$exe) {
    try {
        $r = Lancer $exe @('-version')
        if ($r.Sortie -match 'version "(\d+)(?:\.(\d+))?') {
            $majeur = [int]$Matches[1]
            if ($majeur -eq 1 -and $Matches[2]) { $majeur = [int]$Matches[2] }   # 1.8 -> 8
            return $majeur
        }
    } catch { }
    return $null
}

# --- Fichiers d'entree -------------------------------------------------------------------------
if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) { Echec "jar introuvable : $Jar" }
if (-not (Test-Path -LiteralPath $Cle -PathType Leaf)) { Echec "fichier de cle privee introuvable : $Cle" }
if ($KeyId -notmatch '^[A-Za-z0-9._-]{1,64}$') { Echec "KeyId invalide (1 a 64 caracteres parmi A-Za-z0-9._-) : $KeyId" }

$Jar = (Resolve-Path -LiteralPath $Jar).Path
$Cle = (Resolve-Path -LiteralPath $Cle).Path
$racine = Split-Path -Parent $PSScriptRoot

# --- Java >= 25 --------------------------------------------------------------------------------
$candidats = @()
if ($env:JAVA_HOME) { $candidats += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
$candidats += (Join-Path $env:USERPROFILE 'scoop\apps\temurin25-jdk\current\bin\java.exe')
$cmd = Get-Command java -ErrorAction SilentlyContinue
if ($cmd) { $candidats += $cmd.Source }

$java = $null
$trouvees = @()
foreach ($c in $candidats) {
    if (-not (Test-Path -LiteralPath $c -PathType Leaf)) { continue }
    $v = VersionJava $c
    if ($null -eq $v) { continue }
    $trouvees += "Java $v ($c)"
    if ($v -ge 25) { $java = $c; break }
}
if (-not $java) {
    $vues = if ($trouvees.Count) { ' Versions trouvees : ' + ($trouvees -join ' ; ') + '.' } else { ' Aucun Java trouve.' }
    Echec ("Java 25 ou plus est requis pour signer (l'outil du SDK est compile pour Java 25)." + $vues +
        " Installez un JDK 25 et definissez JAVA_HOME, ou placez-le dans scoop\apps\temurin25-jdk.")
}

# --- Jar du SDK --------------------------------------------------------------------------------
$sdk = Join-Path $racine 'plugins-sdk\target\site-plugins-sdk-0.0.1.jar'
if (-not (Test-Path -LiteralPath $sdk -PathType Leaf)) {
    Echec ("jar du SDK introuvable : $sdk`nConstruisez-le (Java 25 et Maven) :`n  cd plugins-sdk`n  mvn -B -q package")
}

# --- Cles publiques ----------------------------------------------------------------------------
if (-not $ClesPubliques) { $ClesPubliques = Join-Path $racine 'src\main\resources\plateforme\cles-publiques.properties' }
if (-not (Test-Path -LiteralPath $ClesPubliques -PathType Leaf)) { Echec "fichier des cles publiques introuvable : $ClesPubliques" }

$classe = 'com.logicielpourtous.site.sdk.OutilSignature'

# --- Signature (le chemin de la cle est passe ; son contenu n'est jamais lu ici) ---------------
$r = Lancer $java @('-cp', $sdk, $classe, 'signer', $Jar, $Cle, $KeyId)
if ($r.Code -ne 0) { Echec "la signature a echoue (code $($r.Code)).`n$($r.Sortie)" }

$sig = "$Jar.sig"
if (-not (Test-Path -LiteralPath $sig -PathType Leaf)) { Echec "la signature n'a pas ete ecrite : $sig" }

# --- Verification avec les cles publiques du coeur ---------------------------------------------
$v = Lancer $java @('-cp', $sdk, $classe, 'verifier', $Jar, $ClesPubliques)
if ($v.Code -ne 0) {
    Remove-Item -LiteralPath $sig -Force -ErrorAction SilentlyContinue   # ne jamais laisser une signature non verifiee
    Echec ("signature REFUSEE (fichier .sig supprime) par les cles publiques du coeur ($ClesPubliques) : " +
        "la cle privee ne correspond probablement a aucune cle publique listee, ou le KeyId est faux.`n$($v.Sortie)")
}

Write-Host "Signature valide. Fichier a publier avec le jar : $sig"
exit 0
