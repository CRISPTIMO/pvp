# ШТРАФОСТАН TOOLS — PvP Helper

Клиентский мод для Minecraft 1.21.11 (Forge 61). Набор PvP-ассистов: авто-тотем, авто-инструмент, наводка прицела, авто-рыбалка, авто-еда, бросок жемчуга, ТНТ-комбо и другие.

## Сборка

```powershell
$env:JAVA_HOME = (Get-ChildItem "$env:ProgramFiles\Eclipse Adoptium" -Directory | Where-Object { $_.Name -like "jdk-21*" } | Select-Object -First 1).FullName
.\gradlew.bat build
```

Результат: `build/libs/pvphelper-1.0.0.jar` → кинуть в `.minecraft/mods`.
