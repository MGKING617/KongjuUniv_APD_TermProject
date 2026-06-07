param(
    [string]$Python = "",
    [string]$DependencyPath = "outputs\python_deps",
    [string]$InputFile = "data\raw\0519) 지역사회건강조사 재범주화_수정본.xlsx",
    [string]$ProcessedOutput = "data\processed\chs_training_0519_recoded.csv"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $root

if (-not $Python) {
    $bundledPython = Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
    if (Test-Path $bundledPython) {
        $Python = $bundledPython
    } else {
        $Python = "python"
    }
}

$deps = Join-Path $root $DependencyPath
if (Test-Path $deps) {
    $env:PYTHONPATH = $deps
}

& $Python ml\src\prepare_0519_recoded_training_data.py `
    --input $InputFile `
    --output $ProcessedOutput `
    --report-dir ml\reports\0519_recode `
    --min-age 19 `
    --max-age 39

& $Python ml\src\train_model.py `
    --input $ProcessedOutput `
    --output-dir ml\artifacts `
    --min-age 19 `
    --max-age 39

& $Python ml\src\benchmark_models.py `
    --input $ProcessedOutput `
    --output-dir ml\reports\benchmark `
    --min-age 19 `
    --max-age 39
