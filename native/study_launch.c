/* Study Steady authors, 2026. SPDX-License-Identifier: LGPL-2.1-or-later
 * A UTF-16 handoff keeps Windows filenames intact across the ANSI WinHandler.
 * Format: UTF-16LE BOM, working directory + NUL, executable + NUL.
 */
#define UNICODE
#include <windows.h>
#include <shellapi.h>
static BOOL CALLBACK focus_window(HWND window,LPARAM pid) {
    DWORD owner=0;GetWindowThreadProcessId(window,&owner);
    if(owner!=(DWORD)pid||!IsWindowVisible(window))return TRUE;
    DWORD thread=GetWindowThreadProcessId(window,NULL),current=GetCurrentThreadId();
    AttachThreadInput(current,thread,TRUE);BringWindowToTop(window);SetForegroundWindow(window);SetFocus(window);AttachThreadInput(current,thread,FALSE);
    return FALSE;
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
    DWORD length=lstrlenW(exe);
    WCHAR *command=(WCHAR*)HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,(length+3)*sizeof(WCHAR));
    if(!command){HeapFree(GetProcessHeap(),0,data);return 8;}
    command[0]='"';lstrcpyW(command+1,exe);command[length+1]='"';
    SHELLEXECUTEINFOW launch={0};launch.cbSize=sizeof(launch);launch.fMask=SEE_MASK_NOCLOSEPROCESS|SEE_MASK_NOASYNC;launch.lpVerb=L"open";launch.lpFile=exe;launch.lpDirectory=work;launch.nShow=SW_SHOWNORMAL;
    AllowSetForegroundWindow(ASFW_ANY);
    ok=ShellExecuteExW(&launch);
    DWORD error=GetLastError();HeapFree(GetProcessHeap(),0,command);HeapFree(GetProcessHeap(),0,data);
    if(!ok){WCHAR message[160];wsprintfW(message,L"Cannot start selected EXE. Windows error: %lu",error);MessageBoxW(NULL,message,L"Study Steady",MB_OK|MB_ICONERROR);return 9;}
    if(!launch.hProcess)return 0;
    DWORD pid=GetProcessId(launch.hProcess);AllowSetForegroundWindow(pid);WaitForInputIdle(launch.hProcess,10000);
    EnumWindows(focus_window,(LPARAM)pid);
    WaitForSingleObject(launch.hProcess,INFINITE);DWORD code=0;GetExitCodeProcess(launch.hProcess,&code);CloseHandle(launch.hProcess);return (int)code;
}
