#Requires AutoHotkey v2.0
#SingleInstance Force

; ============================================================
;  P + Space macro (Windows, AutoHotkey v2)
;
;  F8  = start / stop
;  F10 = quit the macro completely
;
;  Press F8 while your game is in front. The macro only presses
;  keys while that same window is in front, so alt-tabbing away
;  pauses it instead of typing "p" into other apps.
; ============================================================

; ---- Settings (milliseconds; 1000 = 1 second) ----
HoldMs        := 60     ; how long each key is held down
GapMs         := 500    ; wait between pressing P and pressing Space
RepeatEveryMs := 3000   ; wait after Space before starting again

running   := false
targetWin := 0

F8:: {
    global running, targetWin
    if running {
        running := false
        SoundBeep(500, 120)
        ShowTip("P + Space macro: OFF")
        return
    }
    targetWin := WinExist("A")
    running := true
    SoundBeep(1000, 120)
    ShowTip("P + Space macro: ON  (F8 to stop)")
    SetTimer(RunMacro, -1)
}

F10:: {
    SoundBeep(400, 200)
    ExitApp()
}

RunMacro() {
    while running {
        PressKey("p")
        if !Wait(GapMs)
            break
        PressKey("Space")
        if !Wait(RepeatEveryMs)
            break
    }
}

PressKey(key) {
    if !running || !WinActive("ahk_id " targetWin)
        return
    SendEvent("{" key " down}")
    Sleep(HoldMs)
    SendEvent("{" key " up}")
}

; Waits in small steps so F8 stops it right away. Returns false if stopped.
Wait(ms) {
    stopAt := A_TickCount + ms
    while running && A_TickCount < stopAt
        Sleep(20)
    return running
}

ShowTip(text) {
    ToolTip(text, 10, 10)
    SetTimer(() => ToolTip(), -2000)
}
