/*
 * Shoalcade GameCube / Wii check scene (D222 D-7): a steady, game-like load for the advisory speed check of the
 * Windows app's GameCube and Wii emulator (Dolphin). Part of Shoalcade, GPL-3.0-or-later.
 *
 * Our own scene, built with devkitPPC + libogc (zlib-style licence) by tools/build_gc_check.sh, once for the
 * GameCube (gc-check.dol) and once for the Wii (wii-check.dol). Every frame, as a game would:
 *  - CPU: animation maths for every cube (rotations, matrix products) plus a fixed amount of integer work;
 *  - GPU: CUBES textured, shaded cubes filling the picture, the texture changing between cubes (like materials);
 *  - one picture per TV frame (VIDEO_WaitVSync), so a PC that keeps up shows the game's full frame rate.
 * The textures are made at start-up from simple maths (checks, stripes, rings): no picture files, no third-party
 * art. Nothing is read or written (no memory card, no files), and there is no input.
 */
#include <gccore.h>
#include <malloc.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

#define FIFO_SIZE (256 * 1024)
#define CUBES 1080 /* cubes drawn every frame */
#define CPU_STEPS 300000 /* extra integer work every frame (on top of the cubes' maths) */
#define TEX_SIZE 64
#define TEXTURES 4

static void *xfb[2];
static GXRModeObj *rmode;
static u16 tex_data[TEXTURES][TEX_SIZE * TEX_SIZE] ATTRIBUTE_ALIGN(32);
static GXTexObj tex_obj[TEXTURES];
volatile u32 check_sink; /* keeps the integer work from being optimised away */

static u16 rgb565(int r, int g, int b) { return (u16)(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)); }

/* RGB565 textures in the GPU's tiled order (4 x 4 texel blocks, row by row). */
static void make_textures(void) {
	for (int t = 0; t < TEXTURES; t++) {
		u16 *out = tex_data[t];
		int i = 0;
		for (int by = 0; by < TEX_SIZE; by += 4)
			for (int bx = 0; bx < TEX_SIZE; bx += 4)
				for (int y = by; y < by + 4; y++)
					for (int x = bx; x < bx + 4; x++) {
						int r, g, b;
						switch (t) {
						case 0: { int c = ((x >> 3) ^ (y >> 3)) & 1; r = c ? 230 : 40; g = c ? 200 : 60; b = c ? 60 : 160; break; }
						case 1: { int s = (x + y) & 15; r = s * 16; g = 255 - s * 12; b = 120; break; }
						case 2: { int dx = x - 32, dy = y - 32, d = (dx * dx + dy * dy) >> 5; r = (d * 9) & 255; g = 80; b = 255 - ((d * 5) & 255); break; }
						default: { u32 h = (u32)(x * 374761393u + y * 668265263u); h = (h ^ (h >> 13)) * 1274126177u; r = 90 + (h & 63); g = 140 + ((h >> 8) & 63); b = 60 + ((h >> 16) & 63); break; }
						}
						out[i++] = rgb565(r, g, b);
					}
		DCFlushRange(out, sizeof tex_data[t]);
		GX_InitTexObj(&tex_obj[t], out, TEX_SIZE, TEX_SIZE, GX_TF_RGB565, GX_REPEAT, GX_REPEAT, GX_FALSE);
	}
}

/* One cube: six quads, position + colour + texture coordinate sent directly. */
static void cube(u8 shade) {
	static const s8 p[6][4][3] = {
		{{-1, 1, -1}, {1, 1, -1}, {1, 1, 1}, {-1, 1, 1}},     {{-1, -1, 1}, {1, -1, 1}, {1, -1, -1}, {-1, -1, -1}},
		{{-1, -1, 1}, {-1, 1, 1}, {1, 1, 1}, {1, -1, 1}},     {{1, -1, -1}, {1, 1, -1}, {-1, 1, -1}, {-1, -1, -1}},
		{{1, -1, 1}, {1, 1, 1}, {1, 1, -1}, {1, -1, -1}},     {{-1, -1, -1}, {-1, 1, -1}, {-1, 1, 1}, {-1, -1, 1}},
	};
	static const u8 face[6] = {255, 120, 220, 160, 200, 140}; /* fixed "light" per face */
	static const f32 uv[4][2] = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
	GX_Begin(GX_QUADS, GX_VTXFMT0, 24);
	for (int f = 0; f < 6; f++) {
		u8 c = (u8)((face[f] * shade) >> 8);
		for (int v = 0; v < 4; v++) {
			GX_Position3f32(p[f][v][0], p[f][v][1], p[f][v][2]);
			GX_Color4u8(c, c, c, 255);
			GX_TexCoord2f32(uv[v][0], uv[v][1]);
		}
	}
	GX_End();
}

int main(void) {
	VIDEO_Init();
	rmode = VIDEO_GetPreferredMode(NULL); /* Dolphin: GameCube homebrew boots as PAL (50 Hz), Wii as PAL60 */
	xfb[0] = MEM_K0_TO_K1(SYS_AllocateFramebuffer(rmode));
	xfb[1] = MEM_K0_TO_K1(SYS_AllocateFramebuffer(rmode));
	VIDEO_Configure(rmode);
	VIDEO_SetNextFramebuffer(xfb[0]);
	VIDEO_SetBlack(FALSE);
	VIDEO_Flush();
	VIDEO_WaitVSync();
	if (rmode->viTVMode & VI_NON_INTERLACE) VIDEO_WaitVSync();

	void *fifo = memalign(32, FIFO_SIZE);
	memset(fifo, 0, FIFO_SIZE);
	GX_Init(fifo, FIFO_SIZE);
	GXColor bg = {20, 24, 40, 255};
	GX_SetCopyClear(bg, 0x00ffffff);
	GX_SetViewport(0, 0, rmode->fbWidth, rmode->efbHeight, 0, 1);
	f32 yscale = GX_GetYScaleFactor(rmode->efbHeight, rmode->xfbHeight);
	u32 xfb_h = GX_SetDispCopyYScale(yscale);
	GX_SetScissor(0, 0, rmode->fbWidth, rmode->efbHeight);
	GX_SetDispCopySrc(0, 0, rmode->fbWidth, rmode->efbHeight);
	GX_SetDispCopyDst(rmode->fbWidth, xfb_h);
	GX_SetCopyFilter(rmode->aa, rmode->sample_pattern, GX_TRUE, rmode->vfilter);
	GX_SetFieldMode(rmode->field_rendering, (rmode->viHeight == 2 * rmode->xfbHeight) ? GX_ENABLE : GX_DISABLE);
	GX_SetCullMode(GX_CULL_BACK);
	GX_CopyDisp(xfb[0], GX_TRUE);
	GX_SetDispCopyGamma(GX_GM_1_0);

	GX_ClearVtxDesc();
	GX_SetVtxDesc(GX_VA_POS, GX_DIRECT);
	GX_SetVtxDesc(GX_VA_CLR0, GX_DIRECT);
	GX_SetVtxDesc(GX_VA_TEX0, GX_DIRECT);
	GX_SetVtxAttrFmt(GX_VTXFMT0, GX_VA_POS, GX_POS_XYZ, GX_F32, 0);
	GX_SetVtxAttrFmt(GX_VTXFMT0, GX_VA_CLR0, GX_CLR_RGBA, GX_RGBA8, 0);
	GX_SetVtxAttrFmt(GX_VTXFMT0, GX_VA_TEX0, GX_TEX_ST, GX_F32, 0);
	GX_SetNumChans(1);
	GX_SetNumTexGens(1);
	GX_SetTexCoordGen(GX_TEXCOORD0, GX_TG_MTX2x4, GX_TG_TEX0, GX_IDENTITY);
	GX_SetTevOrder(GX_TEVSTAGE0, GX_TEXCOORD0, GX_TEXMAP0, GX_COLOR0A0);
	GX_SetTevOp(GX_TEVSTAGE0, GX_MODULATE);
	make_textures();

	Mtx44 proj;
	guPerspective(proj, 60, (f32)rmode->viWidth / rmode->viHeight, 1.0F, 200.0F);
	GX_LoadProjectionMtx(proj, GX_PERSPECTIVE);
	Mtx view;
	guVector cam = {0, 0, 0}, up = {0, 1, 0}, look = {0, 0, -1};
	guLookAt(view, &cam, &up, &look);

	int fb = 0;
	u32 frame = 0, lcg = 12345;
	for (;;) {
		frame++;
		/* The CPU's share: integer work a game would spend on logic. */
		for (int i = 0; i < CPU_STEPS; i++) lcg = lcg * 1664525u + 1013904223u + (lcg >> 7);
		check_sink = lcg;

		GX_SetViewport(0, 0, rmode->fbWidth, rmode->efbHeight, 0, 1);
		GX_InvVtxCache();
		GX_InvalidateTexAll();
		GX_SetZMode(GX_TRUE, GX_LEQUAL, GX_TRUE);
		for (int i = 0; i < CUBES; i++) {
			GX_LoadTexObj(&tex_obj[i % TEXTURES], GX_TEXMAP0); /* a "material" change per cube */
			int gx = i % 20, gy = (i / 20) % 6, layer = i / 120;
			f32 a = (frame + i * 7) * 0.02f;
			Mtx rx, ry, model, mv;
			guVector ax = {1, 0, 0}, ay = {0, 1, 0};
			guMtxRotAxisRad(rx, &ax, a);
			guMtxRotAxisRad(ry, &ay, a * 1.3f);
			guMtxConcat(rx, ry, model);
			guMtxScaleApply(model, model, 0.9f, 0.9f, 0.9f);
			guMtxTransApply(model, model, (gx - 9.5f) * 2.2f, (gy - 2.5f) * 2.2f, -18.0f - layer * 6.0f);
			guMtxConcat(view, model, mv);
			GX_LoadPosMtxImm(mv, GX_PNMTX0);
			cube((u8)(160 + (i * 37) % 96));
		}
		GX_CopyDisp(xfb[fb], GX_TRUE);
		GX_DrawDone();
		VIDEO_SetNextFramebuffer(xfb[fb]);
		VIDEO_Flush();
		VIDEO_WaitVSync();
		fb ^= 1;
	}
	return 0;
}
