param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Question = "이 앱은 우울 관련 참고 지표를 어떻게 보여줘?"
)

$body = @{
    content = $Question
    tone = "polite"
} | ConvertTo-Json -Compress

function Invoke-ChatOnce {
    param([string]$Label)

    $startedAt = Get-Date
    Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/guest/chat" `
        -ContentType "application/json; charset=utf-8" `
        -Body $body | Out-Null
    $elapsed = (Get-Date) - $startedAt
    [PSCustomObject]@{
        label = $Label
        elapsedMs = [math]::Round($elapsed.TotalMilliseconds, 0)
    }
}

Invoke-ChatOnce -Label "first"
Invoke-ChatOnce -Label "second"
