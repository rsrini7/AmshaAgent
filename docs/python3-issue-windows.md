whr python3 
C:\Users\Srini\AppData\Local\Microsoft\WindowsApps\python3.exe

C:\\Users\\Srini\\AppData\\Local\\Microsoft\\WindowsApps\\python3.exe --version 
Python was not found; run without arguments to install from the Microsoft Store, or disable this shortcut from Settings > Apps > Advanced app settings > App execution aliases.

whr python
C:\Python313\python.exe
C:\Users\Srini\AppData\Local\Programs\Python\Python312\python.exe
C:\Users\Srini\AppData\Local\Microsoft\WindowsApps\python.exe


---

To automate disabling the Python App Execution Aliases via PowerShell, you can directly remove the "reparse points" (the fake .exe files) from your local app data folder. This is functionally equivalent to toggling them "Off" in the Settings menu. [1, 2, 3] 
Run the following commands in an elevated (Administrator) PowerShell window:

# Remove the fake Python aliases redirecting to the Microsoft Store
Remove-Item "$env:LOCALAPPDATA\Microsoft\WindowsApps\python.exe" -Force -ErrorAction SilentlyContinue
Remove-Item "$env:LOCALAPPDATA\Microsoft\WindowsApps\python3.exe" -Force -ErrorAction SilentlyContinue

Why this works
These files are not actual programs but NTFS reparse points that act as triggers for the Microsoft Store. Deleting them prevents Windows from intercepting the python command, allowing your system to fall back to the next valid path in your environment variables (like C:\Python313\python.exe). [1, 4, 5, 6] 
Verification
After running the commands, open a new PowerShell session and check your version again:

python --version# Should now return: Python 3.13.x

Note: If you ever manually toggle these back to "On" in the Windows Settings, Windows will recreate these files. [2] 


Reordering your PATH variable via PowerShell to ensure Python 3.13 always takes priority over 3.12


To reorder your PATH via PowerShell without risk of corruption, you should pull the current variable, filter out the target strings, and rebuild it with your preferred version at the very beginning.
Run this script in an Administrator PowerShell session to prioritize Python 3.13 over all other versions:

# 1. Get the current User PATH
$oldPath = [Environment]::GetEnvironmentVariable("Path", "User")
# 2. Split into a list and filter out any existing Python 3.13 paths to avoid duplicates
$pathList = $oldPath.Split(';', [System.StringSplitOptions]::RemoveEmptyEntries)
$otherPaths = $pathList | Where-Object { $_ -notlike "*Python313*" }
# 3. Define the Python 3.13 paths to move to the front
$priorityPaths = @(
    "C:\Python313",
    "C:\Python313\Scripts"
)
# 4. Join them back together (Priority + Others)
$newPath = ($priorityPaths + $otherPaths) -join ";"
# 5. Save back to the User Environment
[Environment]::SetEnvironmentVariable("Path", $newPath, "User")

Write-Host "PATH updated. Python 3.13 is now the priority." -ForegroundColor Green

Why this is safe:

* No Duplicates: It explicitly filters out Python313 before adding it to the front, so you don't end up with a messy, repetitive string.
* System Integrity: It uses the .NET Environment API rather than raw registry editing, which is the Microsoft-recommended way to handle environment variables.
* Scoped Change: This only modifies the User path. If you need to change the System (global) path instead, change "User" to "Machine" in lines 2 and 17 (requires Admin).

Verification
After running the script, restart your PowerShell window and check the priority:

$env:Path.Split(';')[0..2]  # Shows the first 3 items in your path

---

Script that automatically detects where your Python is installed instead of using hardcoded paths like C:\Python313 

This script scans your system for existing Python installations, finds the most recent one (like 3.13), and moves it to the very front of your User PATH.
Run this in an Administrator PowerShell window:

# 1. Find all installed Python directories (excluding the WindowsApps 'fake' ones)
$pythonDirs = Get-ChildItem -Path "C:\Python*", "$env:LOCALAPPDATA\Programs\Python\Python*" -ErrorAction SilentlyContinue | 
              Where-Object { Test-Path "$($_.FullName)\python.exe" } |
              Sort-Object Name -Descending
if ($pythonDirs.Count -eq 0) { 
    Write-Error "No Python installations found on disk."; return 
}
# 2. Pick the latest version (e.g., Python313)
$latestPython = $pythonDirs[0].FullName
$latestScripts = Join-Path $latestPython "Scripts"
$priorityPaths = @($latestPython, $latestScripts)
# 3. Get current User PATH and filter out the version we're moving to the front
$oldPathString = [Environment]::GetEnvironmentVariable("Path", "User")
$pathList = $oldPathString.Split(';', [System.StringSplitOptions]::RemoveEmptyEntries)
$cleanPathList = $pathList | Where-Object { $_ -notin $priorityPaths }
# 4. Rebuild PATH: [New Priority] + [Original Remaining Paths]
$newPathString = ($priorityPaths + $cleanPathList) -join ";"
# 5. Commit change safely
[Environment]::SetEnvironmentVariable("Path", $newPathString, "User")

Write-Host "Success! Priority set to: $latestPython" -ForegroundColor Green

Why this is better for automation:

* Dynamic Detection: It looks in both C:\ and your AppData folder (where your 3.12 was located) [User Input].
* Auto-Sorting: It uses Sort-Object -Descending so that 3.13 will naturally sit above 3.12 or 3.11.
* Cleanliness: It prevents the PATH from growing indefinitely by removing the target paths before re-inserting them at the front.

Note: Remember to restart your PowerShell terminal after running this for the $env:PATH to refresh.

---

Wrap this into a reusable function you can add to your $PROFILE for future use :

To make this a permanent, reusable tool, you can add it to your PowerShell Profile. This creates a custom command (e.g., Update-PythonPath) that you can run whenever you install a new version of Python.
1. Create/Open your Profile
Run this in PowerShell to open your profile script in Notepad:

if (!(Test-Path $PROFILE)) { New-Item -Type File -Path $PROFILE -Force }
notepad $PROFILE

2. Paste the Function
Copy and paste this code into the Notepad window and save it:

function Update-PythonPath {
    <#
    .SYNOPSIS
        Automatically prioritizes the latest installed Python version in the User PATH.
    #>
    # 1. Search common install locations
    $searchPaths = @("C:\Python*", "$env:LOCALAPPDATA\Programs\Python\Python*")
    $pythonDirs = Get-ChildItem -Path $searchPaths -ErrorAction SilentlyContinue | 
                  Where-Object { Test-Path (Join-Path $_.FullName "python.exe") } |
                  Sort-Object Name -Descending

    if ($null -eq $pythonDirs) { 
        Write-Warning "No Python installations found on disk."
        return 
    }

    # 2. Identify the latest version and its Scripts folder
    $latestPath = $pythonDirs[0].FullName
    $scriptsPath = Join-Path $latestPath "Scripts"
    $priority = @($latestPath, $scriptsPath)

    # 3. Get current User PATH and filter out the target paths to avoid duplicates
    $oldPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $pathList = $oldPath.Split(';', [System.StringSplitOptions]::RemoveEmptyEntries)
    $remainingPaths = $pathList | Where-Object { $_ -notin $priority }

    # 4. Rebuild and Save
    $newPath = ($priority + $remainingPaths) -join ";"
    [Environment]::SetEnvironmentVariable("Path", $newPath, "User")

    Write-Host "Done! Priority set to: $latestPath" -ForegroundColor Green
    Write-Host "Please restart your terminal to apply changes." -ForegroundColor Cyan
}

3. Usage

   1. Restart your PowerShell window.
   2. Simply type:
   
   Update-PythonPath
   
   
Why this is a "best practice":

* Version Agnostic: When Python 3.14 or 4.0 comes out, you just run this command once and it handles the update.
* Safe Duplication: It cleans up your PATH before saving, preventing it from growing into a massive, unreadable string.
* Portable: You can copy this function into any automation script or server setup.

---

App Execution Alias removal (from the previous step) inside this function :

To make this a "one-click" fix for any machine, here is the complete function. It kills the Microsoft Store redirects and prioritizes your latest Python version in one go.
1. Update your Profile
Open your profile again (notepad $PROFILE) and replace the previous version with this "Super" version:

function Fix-PythonEnvironment {
    <#
    .SYNOPSIS
        Removes MS Store aliases and prioritizes the latest local Python install.
    #>
    Write-Host "--- Starting Python Environment Fix ---" -ForegroundColor Cyan

    # 1. Kill the 'App Execution Aliases' (Microsoft Store Redirects)
    $aliases = @(
        "$env:LOCALAPPDATA\Microsoft\WindowsApps\python.exe",
        "$env:LOCALAPPDATA\Microsoft\WindowsApps\python3.exe"
    )
    foreach ($alias in $aliases) {
        if (Test-Path $alias) {
            Remove-Item $alias -Force -ErrorAction SilentlyContinue
            Write-Host "Removed Alias: $alias" -ForegroundColor Yellow
        }
    }

    # 2. Find all installed Python directories
    $searchPaths = @("C:\Python*", "$env:LOCALAPPDATA\Programs\Python\Python*")
    $pythonDirs = Get-ChildItem -Path $searchPaths -ErrorAction SilentlyContinue | 
                  Where-Object { Test-Path (Join-Path $_.FullName "python.exe") } |
                  Sort-Object Name -Descending

    if ($null -eq $pythonDirs) { 
        Write-Error "No Python installations found on disk."
        return 
    }

    # 3. Identify the latest version (e.g. 3.13 over 3.12)
    $latestPath = $pythonDirs[0].FullName
    $scriptsPath = Join-Path $latestPath "Scripts"
    $priority = @($latestPath, $scriptsPath)

    # 4. Clean and Rebuild User PATH
    $oldPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $pathList = $oldPath.Split(';', [System.StringSplitOptions]::RemoveEmptyEntries)
    $remainingPaths = $pathList | Where-Object { $_ -notin $priority }
    
    $newPath = ($priority + $remainingPaths) -join ";"
    [Environment]::SetEnvironmentVariable("Path", $newPath, "User")

    Write-Host "Success! Priority set to: $latestPath" -ForegroundColor Green
    Write-Host "Please RESTART your terminal to apply changes." -ForegroundColor Cyan
}

2. How to use it
Once saved, restart PowerShell and just run:

Fix-PythonEnvironment

What this achieves:

   1. Deletes the "Python was not found" triggers [User Input].
   2. Scans for all Python versions on your C: drive or User profile [User Input].
   3. Sorts them so the highest version (e.g., 3.13) always wins.
   4. Moves that version to the front of your PATH so python always calls the one you want.
