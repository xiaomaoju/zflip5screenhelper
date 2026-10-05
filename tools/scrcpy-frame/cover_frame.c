/* Local scrcpy 4.1 presentation extension. Apache-2.0, like upstream scrcpy. */
#include "cover_frame.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "screen.h"
#include "util/log.h"

// Device-reported screen pixels; body and lenses follow the user's photo.
// Kept in sync with zflip5-cover-overlay.svg and its calibration note.
#define BODY_W 836.f
#define BODY_H 992.f
#define OPEN_X 44.f
#define OPEN_Y 98.f
#define OPEN_W 748.f
#define OPEN_H 720.f

bool
cover_frame_init(struct cover_frame *frame, SDL_Renderer *renderer) {
    memset(frame, 0, sizeof(*frame));
    const char *path = getenv("FLIPCOVER_FRAME_PNG");
    if (!path || !*path) {
        return true;
    }
    frame->surface = SDL_LoadPNG(path);
    if (!frame->surface) {
        LOGE("Cannot load cover frame: %s", SDL_GetError());
        return false;
    }
    frame->texture = SDL_CreateTextureFromSurface(renderer, frame->surface);
    if (!frame->texture) {
        cover_frame_destroy(frame);
        return false;
    }
    SDL_SetTextureBlendMode(frame->texture, SDL_BLENDMODE_BLEND);
    SDL_SetTextureScaleMode(frame->texture, SDL_SCALEMODE_LINEAR);
    frame->enabled = true;

    int w, h, l, t, r, b;
    const char *safe = getenv("FLIPCOVER_SAFE_AREA");
    if (safe && sscanf(safe, "%d,%d,%d,%d,%d,%d", &w, &h, &l, &t, &r, &b) == 6
            && w > 0 && h > 0 && l >= 0 && t >= 0 && r >= 0 && b >= 0
            && l + r < w && t + b < h) {
        frame->safe[0] = (float) l / w;
        frame->safe[1] = (float) t / h;
        frame->safe[2] = (float) r / w;
        frame->safe[3] = (float) b / h;
        frame->source_ratio = (float) w / h;
        frame->has_safe_area = true;
    }
    LOGI("Cover frame ready. F8: frame; F9: device cutout guide. Frame is illustrative.");
    return true;
}

void
cover_frame_destroy(struct cover_frame *frame) {
    SDL_DestroyTexture(frame->texture);
    SDL_DestroySurface(frame->surface);
    frame->texture = NULL;
    frame->surface = NULL;
}

struct sc_size
cover_frame_size(enum sc_orientation orientation) {
    bool swapped = sc_orientation_is_swap(orientation);
    return (struct sc_size) {swapped ? 992 : 836, swapped ? 836 : 992};
}

static SDL_FRect
rotate_rect(SDL_FRect r, float w, float h, unsigned rotation) {
    switch (rotation) {
        case 1: return (SDL_FRect) {h - r.y - r.h, r.x, r.h, r.w};
        case 2: return (SDL_FRect) {w - r.x - r.w, h - r.y - r.h, r.w, r.h};
        case 3: return (SDL_FRect) {r.y, w - r.x - r.w, r.h, r.w};
        default: return r;
    }
}

void
cover_frame_layout(struct sc_screen *screen, struct sc_size window) {
    struct cover_frame *frame = &screen->cover;
    frame->rotation = sc_orientation_get_rotation(screen->orientation);
    struct sc_size body = cover_frame_size(screen->orientation);
    float scale = fminf((float) window.width / body.width,
                       (float) window.height / body.height);
    frame->body = (SDL_FRect) {(window.width - body.width * scale) / 2.f,
        (window.height - body.height * scale) / 2.f,
        body.width * scale, body.height * scale};
    float video_scale = fminf(OPEN_W / screen->frame_size.width,
                             OPEN_H / screen->frame_size.height);
    float w = screen->frame_size.width * video_scale;
    float h = screen->frame_size.height * video_scale;
    SDL_FRect video = {OPEN_X + (OPEN_W - w) / 2.f,
                      OPEN_Y + (OPEN_H - h) / 2.f, w, h};
    video = rotate_rect(video, BODY_W, BODY_H, frame->rotation);
    SDL_FRect next = {frame->body.x + video.x * scale,
                     frame->body.y + video.y * scale, video.w * scale, video.h * scale};
    if (memcmp(&next, &screen->rect, sizeof(next))) {
        cover_frame_cancel_input(screen);
    }
    screen->rect = next;
}

bool
cover_frame_hit(struct sc_screen *screen, float x, float y) {
    struct cover_frame *frame = &screen->cover;
    SDL_FRect r = screen->rect;
    if (x < r.x || y < r.y || x >= r.x + r.w || y >= r.y + r.h) {
        return false;
    }
    if (!frame->enabled) {
        return true;
    }
    float u = (x - frame->body.x) / frame->body.w;
    float v = (y - frame->body.y) / frame->body.h;
    float a = u;
    switch (frame->rotation) {
        case 1: u = v; v = 1.f - a; break;
        case 2: u = 1.f - u; v = 1.f - v; break;
        case 3: u = 1.f - v; v = a; break;
    }
    if (u < 0 || v < 0 || u >= 1 || v >= 1) {
        return false;
    }
    Uint8 red, green, blue, alpha;
    if (!SDL_ReadSurfacePixel(frame->surface, u * frame->surface->w,
            v * frame->surface->h, &red, &green, &blue, &alpha)) {
        return false;
    }
    return alpha < 40;
}

void
cover_frame_render(struct sc_screen *screen, float density) {
    struct cover_frame *frame = &screen->cover;
    if (!frame->texture || !screen->video || screen->disconnected) {
        return;
    }
    SDL_Renderer *renderer = screen->renderer;
    if (frame->enabled) {
        SDL_FRect dest = frame->body;
        if (frame->rotation & 1) {
            dest.x += (dest.w - dest.h) / 2.f;
            dest.y += (dest.h - dest.w) / 2.f;
            float tmp = dest.w;
            dest.w = dest.h;
            dest.h = tmp;
        }
        dest.x *= density; dest.y *= density;
        dest.w *= density; dest.h *= density;
        SDL_RenderTextureRotated(renderer, frame->texture, NULL, &dest,
                                 frame->rotation * 90., NULL, SDL_FLIP_NONE);
    }
    if (frame->guides && frame->has_safe_area
            && fabsf((float) screen->frame_size.width / screen->frame_size.height
                     - frame->source_ratio) < 0.02f) {
        SDL_FRect safe = {frame->safe[0], frame->safe[1],
            1.f - frame->safe[0] - frame->safe[2],
            1.f - frame->safe[1] - frame->safe[3]};
        safe = rotate_rect(safe, 1.f, 1.f,
                           sc_orientation_get_rotation(screen->orientation));
        SDL_FRect r = screen->rect;
        SDL_FRect guide = {(r.x + safe.x * r.w) * density,
                          (r.y + safe.y * r.h) * density,
                          safe.w * r.w * density, safe.h * r.h * density};
        SDL_SetRenderDrawColor(renderer, 70, 225, 197, 255);
        SDL_RenderRect(renderer, &guide);
        guide.x += 1; guide.y += 1; guide.w -= 2; guide.h -= 2;
        SDL_RenderRect(renderer, &guide);
    }
}

void
cover_frame_cancel_input(struct sc_screen *screen) {
    struct cover_frame *frame = &screen->cover;
    frame->edge_pending = false;
    for (Uint8 button = 1; button <= 5; ++button) {
        if (frame->active_buttons & SDL_BUTTON_MASK(button)) {
            if (button == SDL_BUTTON_LEFT && screen->controller) {
                struct sc_control_msg cancel = {
                    .type = SC_CONTROL_MSG_TYPE_INJECT_TOUCH_EVENT,
                    .inject_touch_event = {
                        .action = AMOTION_EVENT_ACTION_CANCEL,
                        .pointer_id = screen->im.vfinger_down
                            ? SC_POINTER_ID_GENERIC_FINGER : SC_POINTER_ID_MOUSE,
                        .position = {
                            .screen_size = screen->frame_size,
                            .point = sc_screen_convert_window_to_frame_coords(screen,
                                frame->last_x, frame->last_y),
                        },
                    },
                };
                if (!sc_controller_push_msg(screen->controller, &cancel)) {
                    LOGW("Could not cancel the cover-frame gesture");
                }
                screen->im.mouse_buttons_state &= ~SC_MOUSE_BUTTON_LEFT;
                screen->im.vfinger_down = false;
                continue;
            }
            SDL_Event up = {0};
            up.type = SDL_EVENT_MOUSE_BUTTON_UP;
            up.button.button = button;
            up.button.x = frame->last_x;
            up.button.y = frame->last_y;
            sc_input_manager_handle_event(&screen->im, &up);
        }
    }
    frame->blocked_buttons |= frame->active_buttons;
    frame->active_buttons = 0;
}

// A press on the bezel is deferred until it crosses the screen opening.
// Start at that crossing, not at the first (possibly distant) motion sample,
// so Android can recognize an edge swipe even when the mouse moves quickly.
static void
cover_frame_begin_edge_drag(struct sc_screen *screen,
                            const SDL_MouseMotionEvent *motion) {
    struct cover_frame *frame = &screen->cover;
    float dx = motion->x - frame->edge_x;
    float dy = motion->y - frame->edge_y;
    float outside = 0.f, inside = 1.f;
    for (unsigned i = 1; i <= 64; ++i) {
        float t = i / 64.f;
        if (cover_frame_hit(screen, frame->edge_x + dx * t,
                            frame->edge_y + dy * t)) {
            inside = t;
            break;
        }
        outside = t;
    }
    for (unsigned i = 0; i < 12; ++i) {
        float t = (outside + inside) / 2.f;
        if (cover_frame_hit(screen, frame->edge_x + dx * t,
                            frame->edge_y + dy * t)) {
            inside = t;
        } else {
            outside = t;
        }
    }
    frame->edge_pending = false;
    frame->blocked_buttons &= ~SDL_BUTTON_LMASK;
    frame->active_buttons |= SDL_BUTTON_LMASK;
    frame->last_x = frame->edge_x + dx * inside;
    frame->last_y = frame->edge_y + dy * inside;
    SDL_Event down = {0};
    down.type = SDL_EVENT_MOUSE_BUTTON_DOWN;
    down.button.timestamp = motion->timestamp;
    down.button.windowID = motion->windowID;
    down.button.which = motion->which;
    down.button.button = SDL_BUTTON_LEFT;
    down.button.down = true;
    down.button.clicks = 1;
    down.button.x = frame->last_x;
    down.button.y = frame->last_y;
    sc_input_manager_handle_event(&screen->im, &down);
}

// An established drag owns its release even outside the opening. Keep the
// Android touch inside the screen, including the rounded corners and cutout.
static bool
cover_frame_drag_hit(struct sc_screen *screen, float x, float y) {
    if (!cover_frame_hit(screen, x, y)) {
        return false;
    }
    struct sc_point point = sc_screen_convert_window_to_frame_coords(screen, x, y);
    struct sc_size size = screen->frame_size;
    if (point.x < 0 || point.y < 0 || point.x >= size.width || point.y >= size.height) {
        return false;
    }
    // Round-trip the injected pixel's center through the same aperture mask.
    // Float-only hit testing can land one pixel inside a cutout after truncation.
    SDL_FRect sample = {(point.x + .5f) / size.width, (point.y + .5f) / size.height, 0, 0};
    sample = rotate_rect(sample, 1.f, 1.f, sc_orientation_get_rotation(screen->orientation));
    SDL_FRect r = screen->rect;
    return cover_frame_hit(screen, r.x + sample.x * r.w, r.y + sample.y * r.h);
}

static SDL_FPoint
cover_frame_drag_point(struct sc_screen *screen, float x, float y) {
    struct cover_frame *frame = &screen->cover;
    SDL_FRect r = screen->rect;
    // scrcpy truncates both window coordinates and the delta from rect.x/y.
    // Leave one source pixel plus that rounding margin inside each edge.
    float mx = fminf(1.f + ceilf(r.w / screen->content_size.width), r.w / 2.f);
    float my = fminf(1.f + ceilf(r.h / screen->content_size.height), r.h / 2.f);
    x = fmaxf(r.x + mx, fminf(x, r.x + r.w - mx));
    y = fmaxf(r.y + my, fminf(y, r.y + r.h - my));
    if (cover_frame_drag_hit(screen, x, y)) {
        return (SDL_FPoint) {x, y};
    }
    float start_x = frame->last_x, start_y = frame->last_y;
    if (!cover_frame_drag_hit(screen, start_x, start_y)) {
        // The first edge-entry sample may itself straddle an integer pixel.
        start_x = r.x + r.w / 2.f;
        start_y = r.y + r.h / 2.f;
    }
    float inside = 0.f, outside = 1.f;
    float dx = x - start_x;
    float dy = y - start_y;
    for (unsigned i = 0; i < 16; ++i) {
        float t = (inside + outside) / 2.f;
        if (cover_frame_drag_hit(screen, start_x + dx * t,
                                 start_y + dy * t)) {
            inside = t;
        } else {
            outside = t;
        }
    }
    return (SDL_FPoint) {start_x + dx * inside, start_y + dy * inside};
}

bool
cover_frame_filter_event(struct sc_screen *screen, const SDL_Event *event) {
    struct cover_frame *frame = &screen->cover;
    if (!frame->texture || !screen->window_shown || screen->disconnected) {
        return false;
    }
    switch (event->type) {
        case SDL_EVENT_WINDOW_FOCUS_LOST:
            cover_frame_cancel_input(screen);
            frame->blocked_buttons = 0;
            return false;
        case SDL_EVENT_MOUSE_BUTTON_DOWN:
            frame->edge_pending = false;
            if (!cover_frame_hit(screen, event->button.x, event->button.y)) {
                if (event->button.button == SDL_BUTTON_LEFT
                        && !frame->active_buttons
                        && !(frame->blocked_buttons & ~SDL_BUTTON_LMASK)) {
                    frame->edge_pending = true;
                    frame->edge_x = event->button.x;
                    frame->edge_y = event->button.y;
                }
                frame->blocked_buttons |= SDL_BUTTON_MASK(event->button.button);
                return true;
            }
            frame->blocked_buttons &= ~SDL_BUTTON_MASK(event->button.button);
            frame->active_buttons |= SDL_BUTTON_MASK(event->button.button);
            frame->last_x = event->button.x;
            frame->last_y = event->button.y;
            break;
        case SDL_EVENT_MOUSE_BUTTON_UP: {
            SDL_MouseButtonFlags mask = SDL_BUTTON_MASK(event->button.button);
            frame->edge_pending = false;
            if (event->button.button == SDL_BUTTON_LEFT
                    && !(frame->active_buttons & mask)) {
                frame->blocked_buttons &= ~mask;
                return true;
            }
            if (event->button.button == SDL_BUTTON_LEFT) {
                SDL_Event release = *event;
                SDL_FPoint point = {event->button.x, event->button.y};
                if (point.x == 0 && point.y == 0) {
                    point = (SDL_FPoint) {frame->last_x, frame->last_y};
                }
                if (!cover_frame_drag_hit(screen, point.x, point.y)) {
                    point = cover_frame_drag_point(screen, point.x, point.y);
                }
                release.button.x = frame->last_x = point.x;
                release.button.y = frame->last_y = point.y;
                frame->active_buttons &= ~mask;
                frame->blocked_buttons &= ~mask;
                sc_input_manager_handle_event(&screen->im, &release);
                return true;
            }
            // Some macOS synthetic releases omit position. The latest motion
            // remains authoritative for the other mouse buttons too.
            if ((frame->active_buttons & mask) && event->button.x == 0
                    && event->button.y == 0
                    && cover_frame_hit(screen, frame->last_x, frame->last_y)) {
                SDL_Event release = *event;
                release.button.x = frame->last_x;
                release.button.y = frame->last_y;
                frame->active_buttons &= ~mask;
                sc_input_manager_handle_event(&screen->im, &release);
                return true;
            }
            if ((frame->active_buttons & mask)
                    && !cover_frame_hit(screen, event->button.x, event->button.y)) {
                cover_frame_cancel_input(screen);
            }
            frame->active_buttons &= ~mask;
            if (frame->blocked_buttons & mask) {
                frame->blocked_buttons &= ~mask;
                return true;
            }
            break;
        }
        case SDL_EVENT_MOUSE_MOTION: {
            bool hit = cover_frame_hit(screen, event->motion.x, event->motion.y);
            if ((frame->active_buttons & SDL_BUTTON_LMASK)
                    && !(event->motion.state & SDL_BUTTON_LMASK)) {
                // Recover if a release was lost; never leave Android held down.
                cover_frame_cancel_input(screen);
                frame->blocked_buttons &= ~SDL_BUTTON_LMASK;
                return true;
            }
            if (frame->edge_pending) {
                if (event->motion.state != SDL_BUTTON_LMASK) {
                    frame->edge_pending = false;
                } else if (!hit) {
                    frame->edge_x = event->motion.x;
                    frame->edge_y = event->motion.y;
                    return true;
                } else {
                    cover_frame_begin_edge_drag(screen, &event->motion);
                }
            }
            if (hit && (frame->active_buttons & SDL_BUTTON_LMASK)) {
                hit = cover_frame_drag_hit(screen, event->motion.x, event->motion.y);
            }
            if ((frame->blocked_buttons & event->motion.state)
                    || ((event->motion.state & SDL_BUTTON_LMASK)
                        && !(frame->active_buttons & SDL_BUTTON_LMASK))) {
                return true;
            }
            if (!hit) {
                if (frame->active_buttons & SDL_BUTTON_LMASK) {
                    SDL_FPoint point = cover_frame_drag_point(screen,
                        event->motion.x, event->motion.y);
                    SDL_Event motion = *event;
                    motion.motion.x = point.x;
                    motion.motion.y = point.y;
                    motion.motion.xrel = point.x - frame->last_x;
                    motion.motion.yrel = point.y - frame->last_y;
                    frame->last_x = point.x;
                    frame->last_y = point.y;
                    sc_input_manager_handle_event(&screen->im, &motion);
                } else {
                    cover_frame_cancel_input(screen);
                }
                return true;
            }
            frame->last_x = event->motion.x;
            frame->last_y = event->motion.y;
            break;
        }
        case SDL_EVENT_MOUSE_WHEEL:
            return !cover_frame_hit(screen, event->wheel.mouse_x, event->wheel.mouse_y);
    }
    return false;
}
