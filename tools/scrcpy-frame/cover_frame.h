#ifndef FLIPCOVER_FRAME_H
#define FLIPCOVER_FRAME_H

#include <SDL3/SDL.h>
#include <stdbool.h>
#include "coords.h"
#include "options.h"

struct sc_screen;

struct cover_frame {
    SDL_Surface *surface;
    SDL_Texture *texture;
    bool enabled;
    bool guides;
    bool has_safe_area;
    float safe[4]; // Normalized left, top, right and bottom cutout insets.
    float source_ratio;
    SDL_FRect body;
    unsigned rotation;
    SDL_MouseButtonFlags active_buttons;
    SDL_MouseButtonFlags blocked_buttons;
    bool edge_pending;
    float edge_x;
    float edge_y;
    float last_x;
    float last_y;
};

bool cover_frame_init(struct cover_frame *frame, SDL_Renderer *renderer);
void cover_frame_destroy(struct cover_frame *frame);
struct sc_size cover_frame_size(enum sc_orientation orientation);
void cover_frame_layout(struct sc_screen *screen, struct sc_size window);
void cover_frame_render(struct sc_screen *screen, float pixel_density);
void cover_frame_cancel_input(struct sc_screen *screen);
bool cover_frame_filter_event(struct sc_screen *screen, const SDL_Event *event);
bool cover_frame_hit(struct sc_screen *screen, float x, float y);

#endif
