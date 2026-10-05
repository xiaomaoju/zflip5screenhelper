/* macOS presentation menu. Apache-2.0, like the local scrcpy extension. */
#include "cover_menu.h"
#include "screen.h"
#include "events.h"
#include "util/log.h"
#import <Cocoa/Cocoa.h>

@interface CoverMenuTarget : NSObject <NSMenuDelegate>
@property(nonatomic, assign) struct sc_screen *screen;
@property(nonatomic, strong) NSMenuItem *root;
@end

static CoverMenuTarget *target;

@implementation CoverMenuTarget
- (void)chooseOrientation:(NSMenuItem *)sender {
    // Queue onto scrcpy's normal event loop; never resize during Cocoa dispatch.
    SDL_Event event = {0};
    event.type = SC_EVENT_COVER_ROTATION;
    event.user.data1 = self.screen;
    event.user.code = (Sint32) sender.tag;
    if (!SDL_PushEvent(&event)) {
        LOGW("Could not queue cover rotation: %s", SDL_GetError());
    }
}
- (void)menuNeedsUpdate:(NSMenu *)menu {
    struct sc_screen *screen = self.screen;
    bool available = screen && screen->window_shown && screen->video && !screen->disconnected;
    for (NSMenuItem *item in menu.itemArray) {
        if (item.action) {
            item.enabled = available;
        }
    }
}
@end

void
cover_menu_update(struct sc_screen *screen) {
    if (!target || target.screen != screen) {
        return;
    }
    unsigned rotation = sc_orientation_get_rotation(screen->orientation);
    for (NSMenuItem *item in target.root.submenu.itemArray) {
        if (item.action && item.tag >= 0) {
            item.state = item.tag == rotation ? NSControlStateValueOn : NSControlStateValueOff;
        }
    }
    char title[256];
    snprintf(title, sizeof(title),
        "Z Flip5 外屏 · 投屏 %u° · F7 顺时针 / F8 外框 / F9 辅助线%s",
        rotation * 90, screen->cover.guides ? "（已开）" : "");
    SDL_SetWindowTitle(screen->window, title);
}

void
cover_menu_init(struct sc_screen *screen) {
    NSMenu *main = NSApp.mainMenu;
    if (!screen->cover.texture || !main) {
        return;
    }
    target = [CoverMenuTarget new];
    target.screen = screen;
    target.root = [[NSMenuItem alloc] initWithTitle:@"投屏方向" action:nil keyEquivalent:@""];
    NSMenu *menu = [[NSMenu alloc] initWithTitle:@"投屏方向"];
    menu.autoenablesItems = NO;
    menu.delegate = target;
    NSArray<NSString *> *names = @[@"向左旋转 90°", @"向右旋转 90°（F7）", @"0°（复位）", @"90°", @"180°", @"270°"];
    for (NSUInteger i = 0; i < names.count; ++i) {
        if (i == 2) {
            [menu addItem:NSMenuItem.separatorItem];
        }
        NSMenuItem *item = [[NSMenuItem alloc] initWithTitle:names[i] action:@selector(chooseOrientation:) keyEquivalent:@""];
        item.tag = i < 2 ? -(NSInteger) i - 1 : (NSInteger) i - 2;
        item.target = target;
        if (i < 2) {
            item.toolTip = i == 0 ? @"快捷键：Option + 左方向键" : @"快捷键：F7 或 Option + 右方向键";
        }
        [menu addItem:item];
    }
    [menu addItem:NSMenuItem.separatorItem];
    NSMenuItem *note = [[NSMenuItem alloc] initWithTitle:@"画面与机型框一起转 · 手机方向不变" action:nil keyEquivalent:@""];
    note.enabled = NO;
    [menu addItem:note];
    target.root.submenu = menu;
    [main insertItem:target.root atIndex:MIN((NSInteger) 1, main.numberOfItems)];
    cover_menu_update(screen);
}

bool
cover_menu_handle_event(struct sc_screen *screen, const SDL_Event *event) {
    bool rotate_key = (event->type == SDL_EVENT_KEY_DOWN || event->type == SDL_EVENT_KEY_UP)
                      && event->key.key == SDLK_F7;
    if (!rotate_key && event->type != SC_EVENT_COVER_ROTATION) {
        return false;
    }
    if (!target || target.screen != screen || (!rotate_key && event->user.data1 != screen)
            || !screen->window_shown || !screen->video || screen->disconnected) {
        return true;
    }
    // One rotation per physical press; consume repeats and release locally.
    if (rotate_key && (event->type != SDL_EVENT_KEY_DOWN || event->key.repeat)) {
        return true;
    }
    int rotation = rotate_key ? -2 : event->user.code;
    if (rotation == -1 || rotation == -2) {
        rotation = (sc_orientation_get_rotation(screen->orientation)
                    + (rotation == -1 ? 3 : 1)) % 4;
    }
    if (rotation >= 0 && rotation <= 3) {
        sc_screen_set_orientation(screen, (enum sc_orientation) rotation);
    }
    return true;
}

void
cover_menu_destroy(struct sc_screen *screen) {
    if (target && target.screen == screen) {
        target.screen = NULL;
        target.root.submenu.delegate = nil;
        [NSApp.mainMenu removeItem:target.root];
        target = nil;
    }
}
