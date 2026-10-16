# Purge utility source

`PurgeTool.cs` is the complete source for `../Purge.exe`. It uses only the Windows .NET Framework libraries shipped with Windows and can be rebuilt with:

```bat
C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /nologo /target:winexe /out:Purge.exe /reference:System.dll /reference:System.Core.dll /reference:System.Drawing.dll /reference:System.Windows.Forms.dll source\PurgeTool.cs
```

The worker accepts an internal target only after independently checking the exact bundle marker and rejecting unsafe system/profile roots. Normal users choose no path: the UI can only target the marked bundle that contains it.
