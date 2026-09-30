#ifndef FLIPCOVER_MENU_H
#define FLIPCOVER_MENU_H

#include <SDL3/SDL.h>
#include <stdbool.h>

struct sc_screen;

// Native presentation controls for the macOS cover launcher.
#ifdef __APPLE__
void cover_menu_init(struct sc_screen *screen);
void cover_menu_update(struct sc_screen *screen);
void cover_menu_destroy(struct sc_screen *screen);
bool cover_menu_handle_event(struct sc_screen *screen, const SDL_Event *event);
#else
static inline void cover_menu_init(struct sc_screen *screen) { (void) screen; }
static inline void cover_menu_update(struct sc_screen *screen) { (void) screen; }
static inline void cover_menu_destroy(struct sc_screen *screen) { (void) screen; }
static inline bool cover_menu_handle_event(struct sc_screen *screen, const SDL_Event *event) {
    (void) screen; (void) event; return false;
}
#endif

#endif
