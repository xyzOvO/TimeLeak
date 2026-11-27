# 匹配层微基准（Windows 入口，绕开 bash 里 -javaagent 路径被 MSYS 改写的问题）。
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$java = 'D:\livetoolchain\jdk\jdk-17.0.21.0.2+1\bin\java.exe'
$cp = [System.IO.File]::ReadAllText((Join-Path $root 'app\build\unitTestClasspath.txt'), [System.Text.Encoding]::UTF8).Trim()
& $java '-cp' $cp 'org.junit.runner.JUnitCore' 'com.chengliu.app.core.MatchBench'
exit $LASTEXITCODE
