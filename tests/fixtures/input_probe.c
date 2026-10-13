#define UNICODE
#define _UNICODE
#include <windows.h>
#include <stdio.h>
static int clicks, rightClicks, keys, frames, lastKey;
static void save(void) {
    CreateDirectoryW(L"savedata",NULL);
    FILE *f=fopen("savedata/state.txt","w");
    if(f){fprintf(f,"%d %d %d",clicks,rightClicks,keys);fclose(f);}
}
static LRESULT CALLBACK event(HWND w,UINT m,WPARAM a,LPARAM b) {
    switch(m) {
    case WM_CREATE:{FILE *f=fopen("savedata/state.txt","r");if(f){fscanf(f,"%d %d %d",&clicks,&rightClicks,&keys);fclose(f);}SetTimer(w,1,16,NULL);return 0;}
    case WM_TIMER:frames++;InvalidateRect(w,NULL,FALSE);return 0;
    case WM_LBUTTONDOWN:clicks++;save();return 0;
    case WM_RBUTTONDOWN:rightClicks++;save();return 0;
    case WM_KEYDOWN:keys++;lastKey=(int)a;save();return 0;
    case WM_PAINT:{PAINTSTRUCT ps;HDC d=BeginPaint(w,&ps);RECT r;GetClientRect(w,&r);FillRect(d,&r,(HBRUSH)GetStockObject(WHITE_BRUSH));SetBkMode(d,TRANSPARENT);
        WCHAR text[500];wsprintfW(text,L"Study Steady input and persistence probe\n\nLeft clicks: %d\nRight clicks: %d\nKey presses: %d (last VK: %d)\nAnimation frames: %d\n\nTap this window or use edge controls.\nCounters persist in savedata/state.txt after restart.",clicks,rightClicks,keys,lastKey,frames);
        r.left=24;r.top=24;DrawTextW(d,text,-1,&r,DT_LEFT);Ellipse(d,24+(frames%500),360,64+(frames%500),400);EndPaint(w,&ps);return 0;}
    case WM_DESTROY:save();PostQuitMessage(0);return 0;
    }return DefWindowProcW(w,m,a,b);
}
int WINAPI wWinMain(HINSTANCE h,HINSTANCE prev,LPWSTR args,int show) {
    WNDCLASSW c={0};c.lpfnWndProc=event;c.hInstance=h;c.lpszClassName=L"StudyInputProbe";c.hCursor=LoadCursor(NULL,IDC_ARROW);RegisterClassW(&c);
    HWND w=CreateWindowW(c.lpszClassName,L"Study Steady QA",WS_OVERLAPPEDWINDOW|WS_VISIBLE,60,50,900,520,NULL,NULL,h,NULL);
    ShowWindow(w,show);MSG m;while(GetMessageW(&m,NULL,0,0)>0){TranslateMessage(&m);DispatchMessageW(&m);}return 0;
}
