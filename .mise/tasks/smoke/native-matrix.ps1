#MISE description="Run native platform matrix checks (shuts down gracefully, RSS limits)"
param(
    [Parameter(ValueFromRemainingArguments=$true)]
    $RestArgs
)

& pwsh scripts/verify_native_matrix.ps1 @RestArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
