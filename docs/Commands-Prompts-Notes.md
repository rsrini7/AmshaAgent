Git commands

git fetch origin
git diff origin/main...HEAD  > diff-phaseX.md

git diff --name-only --staged | xargs cat > all_staged_content.txt

git diff --name-only --staged | ForEach-Object { Get-Content $_ } | Set-Content all_staged_content.txt

git diff --name-only --diff-filter=AM origin/main...HEAD |
Where-Object { $_ -notmatch "\.(md|txt)$" } |
ForEach-Object {
    "===== $_ ====="
    Get-Content -Raw $_
    ""
} | Set-Content modified_files_full_content.txt


---

Stop-Process -Id (Get-NetTCPConnection -LocalPort 8081).OwningProcess -Force; Start-Sleep 3

Get-Process -Id (Get-NetTCPConnection -LocalPort 8081 -ErrorAction SilentlyContinue).OwningProcess | Stop-Process -Force; Start-Sleep 2; cmd.exe /c ".\mvnw.cmd quarkus:dev -pl arcclaw-app -Dquarkus.http.port=8081 > quarkus_boot_3.log 2>&1"

Invoke-RestMethod -Uri "http://localhost:8081/api/v1/debug-agents" -Headers @{"X-ArcClaw-API-Key"="key-internal-changeme"} | ConvertTo-Json

 Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force; Start-Sleep 2; Start-Process java -ArgumentList "-jar","arcclaw-app\target\quarkus-app\quarkus-run.jar" -PassThru | Select-Object Id; Start-Sleep 8
 
 Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }; Start-Sleep 2

---


Please go through my entire project end to end as a Senior Java Architect, Security Reviewer, Refactoring Expert & Senior Java Developer. My plan is the share your reviews with Claude/Gemini and its review with you to debate and solve. Make sure to deep dive and not just high level and not only positive side. Not looking for any Constructive Feedback and Roadmap. Looking for Solid review be honest and you can give even strong feedback to make this project awesome. We are executing Phase X....

