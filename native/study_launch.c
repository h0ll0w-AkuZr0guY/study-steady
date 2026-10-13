/* Study Steady authors, 2026. SPDX-License-Identifier: LGPL-2.1-or-later
 * A UTF-16 handoff keeps Windows filenames intact across the ANSI WinHandler.
 * Format: UTF-16LE BOM, working directory + NUL, executable + NUL.
 */
#ifndef UNICODE
#define UNICODE
#endif
#include <windows.h>
#include <shellapi.h>
static HWND game_window;
static LONG game_area;
static BOOL CALLBACK collect_window(HWND window,LPARAM pid) {
    DWORD owner=0;GetWindowThreadProcessId(window,&owner);
    if(owner!=(DWORD)pid||!IsWindowVisible(window))return TRUE;
    RECT r;GetWindowRect(window,&r);LONG area=(r.right-r.left)*(r.bottom-r.top);
    if(area>game_area){game_area=area;game_window=window;}
    return TRUE;
}
static void focus_game(DWORD pid) {
    game_window=NULL;game_area=0;EnumWindows(collect_window,(LPARAM)pid);if(!game_window)return;
    MSG message;PeekMessageW(&message,NULL,0,0,PM_NOREMOVE);
    DWORD current=GetCurrentThreadId(),thread=GetWindowThreadProcessId(game_window,NULL);
    HWND foreground=GetForegroundWindow();DWORD foreground_thread=foreground?GetWindowThreadProcessId(foreground,NULL):0;
    if(foreground_thread&&foreground_thread!=current)AttachThreadInput(current,foreground_thread,TRUE);
    if(thread!=current&&thread!=foreground_thread)AttachThreadInput(current,thread,TRUE);
    BringWindowToTop(game_window);SetForegroundWindow(game_window);SetActiveWindow(game_window);SetFocus(game_window);
    if(thread!=current&&thread!=foreground_thread)AttachThreadInput(current,thread,FALSE);
    if(foreground_thread&&foreground_thread!=current)AttachThreadInput(current,foreground_thread,FALSE);
}
int WINAPI wWinMain(HINSTANCE h,HINSTANCE prev,LPWSTR args,int show) {
    int argc=0;LPWSTR *argv=CommandLineToArgvW(GetCommandLineW(),&argc);
    if(argc!=2)return 2;
    HANDLE file=CreateFileW(argv[1],GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,NULL);
    LocalFree(argv);if(file==INVALID_HANDLE_VALUE)return 3;
    DWORD size=GetFileSize(file,NULL),read=0;
    if(size<8||size>32768||(size&1)){CloseHandle(file);return 4;}
    WCHAR *data=(WCHAR*)HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,size+4);
    if(!data){CloseHandle(file);return 5;}
    BOOL ok=ReadFile(file,data,size,&read,NULL);CloseHandle(file);
    if(!ok||read!=size||data[0]!=0xfeff){HeapFree(GetProcessHeap(),0,data);return 6;}
    WCHAR *work=data+1,*exe=NULL;DWORD units=size/2;
    for(DWORD i=1;i<units;i++)if(data[i]==0){if(i+1<units)exe=data+i+1;break;}
    if(!exe||!*exe||data[units-1]!=0){HeapFree(GetProcessHeap(),0,data);return 7;}
    SHELLEXECUTEINFOW launch={0};launch.cbSize=sizeof(launch);launch.fMask=SEE_MASK_NOCLOSEPROCESS|SEE_MASK_NOASYNC;launch.lpVerb=L"open";launch.lpFile=exe;launch.lpDirectory=work;launch.nShow=SW_SHOWNORMAL;
    AllowSetForegroundWindow(ASFW_ANY);
    ok=ShellExecuteExW(&launch);
    DWORD error=GetLastError();HeapFree(GetProcessHeap(),0,data);
    if(!ok){WCHAR message[160];wsprintfW(message,L"Cannot start selected EXE. Windows error: %lu",error);MessageBoxW(NULL,message,L"Study Steady",MB_OK|MB_ICONERROR);return 9;}
    if(!launch.hProcess)return 0;
    DWORD pid=GetProcessId(launch.hProcess);AllowSetForegroundWindow(pid);WaitForInputIdle(launch.hProcess,10000);
    focus_game(pid);
    WaitForSingleObject(launch.hProcess,INFINITE);DWORD code=0;GetExitCodeProcess(launch.hProcess,&code);CloseHandle(launch.hProcess);return (int)code;
}
