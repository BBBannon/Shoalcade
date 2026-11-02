/*
 * Shoalcade 3DS check scene (plan-3ds step 7, D151). Built on devkitPro's 3ds-examples
 * graphics/gpu/fragment_light (Public Domain Mark). Shoalcade's changes are GPL-3.0-or-later.
 *
 * A fixed, repeatable load for the app's "3DS check": a grid of lit, spinning, textured cubes (one draw call
 * each, like a real game's many objects, with the texture changing between draws as a game's materials do)
 * plus a physics-style processor loop. The app measures how fast the emulator runs it in real time; nothing
 * here times itself (the emulated clock slows down with the emulator).
 *
 * Textures: four 128 x 128 RGBA8 pictures with mipmaps, made by this program at start-up from simple maths
 * (checks, stripes, rings, value noise). No picture files, no third-party art.
 *
 * Load level: sdmc:/shoalcade/check.txt = "<cubes> <cpu>" (the app writes it before starting), so the pass
 * mark can be calibrated without rebuilding. Defaults below if the file is missing.
 */
#include <3ds.h>
#include <citro3d.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#include "vshader_shbin.h"

#define CLEAR_COLOR 0x1B1B2BFF
#define DEFAULT_CUBES 1200
#define DEFAULT_CPU 200000
#define MAX_CUBES 4000

#define DISPLAY_TRANSFER_FLAGS \
	(GX_TRANSFER_FLIP_VERT(0) | GX_TRANSFER_OUT_TILED(0) | GX_TRANSFER_RAW_COPY(0) | \
	GX_TRANSFER_IN_FORMAT(GX_TRANSFER_FMT_RGBA8) | GX_TRANSFER_OUT_FORMAT(GX_TRANSFER_FMT_RGB8) | \
	GX_TRANSFER_SCALING(GX_TRANSFER_SCALE_NO))

typedef struct { float position[3]; float texcoord[2]; float normal[3]; } vertex;

static const vertex vertex_list[] =
{
	// First face (PZ)
	// First triangle
	{ {-0.5f, -0.5f, +0.5f}, {0.0f, 0.0f}, {0.0f, 0.0f, +1.0f} },
	{ {+0.5f, -0.5f, +0.5f}, {1.0f, 0.0f}, {0.0f, 0.0f, +1.0f} },
	{ {+0.5f, +0.5f, +0.5f}, {1.0f, 1.0f}, {0.0f, 0.0f, +1.0f} },
	// Second triangle
	{ {+0.5f, +0.5f, +0.5f}, {1.0f, 1.0f}, {0.0f, 0.0f, +1.0f} },
	{ {-0.5f, +0.5f, +0.5f}, {0.0f, 1.0f}, {0.0f, 0.0f, +1.0f} },
	{ {-0.5f, -0.5f, +0.5f}, {0.0f, 0.0f}, {0.0f, 0.0f, +1.0f} },

	// Second face (MZ)
	// First triangle
	{ {-0.5f, -0.5f, -0.5f}, {0.0f, 0.0f}, {0.0f, 0.0f, -1.0f} },
	{ {-0.5f, +0.5f, -0.5f}, {1.0f, 0.0f}, {0.0f, 0.0f, -1.0f} },
	{ {+0.5f, +0.5f, -0.5f}, {1.0f, 1.0f}, {0.0f, 0.0f, -1.0f} },
	// Second triangle
	{ {+0.5f, +0.5f, -0.5f}, {1.0f, 1.0f}, {0.0f, 0.0f, -1.0f} },
	{ {+0.5f, -0.5f, -0.5f}, {0.0f, 1.0f}, {0.0f, 0.0f, -1.0f} },
	{ {-0.5f, -0.5f, -0.5f}, {0.0f, 0.0f}, {0.0f, 0.0f, -1.0f} },

	// Third face (PX)
	// First triangle
	{ {+0.5f, -0.5f, -0.5f}, {0.0f, 0.0f}, {+1.0f, 0.0f, 0.0f} },
	{ {+0.5f, +0.5f, -0.5f}, {1.0f, 0.0f}, {+1.0f, 0.0f, 0.0f} },
	{ {+0.5f, +0.5f, +0.5f}, {1.0f, 1.0f}, {+1.0f, 0.0f, 0.0f} },
	// Second triangle
	{ {+0.5f, +0.5f, +0.5f}, {1.0f, 1.0f}, {+1.0f, 0.0f, 0.0f} },
	{ {+0.5f, -0.5f, +0.5f}, {0.0f, 1.0f}, {+1.0f, 0.0f, 0.0f} },
	{ {+0.5f, -0.5f, -0.5f}, {0.0f, 0.0f}, {+1.0f, 0.0f, 0.0f} },

	// Fourth face (MX)
	// First triangle
	{ {-0.5f, -0.5f, -0.5f}, {0.0f, 0.0f}, {-1.0f, 0.0f, 0.0f} },
	{ {-0.5f, -0.5f, +0.5f}, {1.0f, 0.0f}, {-1.0f, 0.0f, 0.0f} },
	{ {-0.5f, +0.5f, +0.5f}, {1.0f, 1.0f}, {-1.0f, 0.0f, 0.0f} },
	// Second triangle
	{ {-0.5f, +0.5f, +0.5f}, {1.0f, 1.0f}, {-1.0f, 0.0f, 0.0f} },
	{ {-0.5f, +0.5f, -0.5f}, {0.0f, 1.0f}, {-1.0f, 0.0f, 0.0f} },
	{ {-0.5f, -0.5f, -0.5f}, {0.0f, 0.0f}, {-1.0f, 0.0f, 0.0f} },

	// Fifth face (PY)
	// First triangle
	{ {-0.5f, +0.5f, -0.5f}, {0.0f, 0.0f}, {0.0f, +1.0f, 0.0f} },
	{ {-0.5f, +0.5f, +0.5f}, {1.0f, 0.0f}, {0.0f, +1.0f, 0.0f} },
	{ {+0.5f, +0.5f, +0.5f}, {1.0f, 1.0f}, {0.0f, +1.0f, 0.0f} },
	// Second triangle
	{ {+0.5f, +0.5f, +0.5f}, {1.0f, 1.0f}, {0.0f, +1.0f, 0.0f} },
	{ {+0.5f, +0.5f, -0.5f}, {0.0f, 1.0f}, {0.0f, +1.0f, 0.0f} },
	{ {-0.5f, +0.5f, -0.5f}, {0.0f, 0.0f}, {0.0f, +1.0f, 0.0f} },

	// Sixth face (MY)
	// First triangle
	{ {-0.5f, -0.5f, -0.5f}, {0.0f, 0.0f}, {0.0f, -1.0f, 0.0f} },
	{ {+0.5f, -0.5f, -0.5f}, {1.0f, 0.0f}, {0.0f, -1.0f, 0.0f} },
	{ {+0.5f, -0.5f, +0.5f}, {1.0f, 1.0f}, {0.0f, -1.0f, 0.0f} },
	// Second triangle
	{ {+0.5f, -0.5f, +0.5f}, {1.0f, 1.0f}, {0.0f, -1.0f, 0.0f} },
	{ {-0.5f, -0.5f, +0.5f}, {0.0f, 1.0f}, {0.0f, -1.0f, 0.0f} },
	{ {-0.5f, -0.5f, -0.5f}, {0.0f, 0.0f}, {0.0f, -1.0f, 0.0f} },
};

#define vertex_list_count (sizeof(vertex_list)/sizeof(vertex_list[0]))

static DVLB_s* vshader_dvlb;
static shaderProgram_s program;
static int uLoc_projection, uLoc_modelView;
static C3D_Mtx projection;
static C3D_LightEnv lightEnv;
static C3D_Light light;
static C3D_LightLut lut_Phong;
static void* vbo_data;
static float angle = 0.0f;
static int cubes = DEFAULT_CUBES, cpu = DEFAULT_CPU;

#define TEX_SIZE 128
#define TEX_COUNT 4
static C3D_Tex tex[TEX_COUNT];

/* Integer hash -> 0..255, for the value-noise texture. */
static u32 hash2(u32 x, u32 y, u32 seed)
{
	u32 h = x * 374761393u + y * 668265263u + seed * 2246822519u;
	h = (h ^ (h >> 13)) * 1274126177u;
	return (h ^ (h >> 16)) & 0xFF;
}

/* Smooth value noise at (x, y) with cells [cell] texels wide; wraps at TEX_SIZE so the texture tiles. */
static float noise(int x, int y, int cell, u32 seed)
{
	int n = TEX_SIZE / cell;
	int cx = x / cell, cy = y / cell;
	float fx = (float)(x % cell) / cell, fy = (float)(y % cell) / cell;
	fx = fx * fx * (3.0f - 2.0f * fx); fy = fy * fy * (3.0f - 2.0f * fy);
	float a = hash2(cx % n, cy % n, seed), b = hash2((cx + 1) % n, cy % n, seed);
	float c = hash2(cx % n, (cy + 1) % n, seed), d = hash2((cx + 1) % n, (cy + 1) % n, seed);
	return (a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy) / 255.0f;
}

/* Colour of texel (x, y) of texture [k], as 0xRRGGBBAA (the GPU's RGBA8 word). */
static u32 texel(int k, int x, int y)
{
	float r, g, b;
	switch (k)
	{
	case 0: { // checks with a soft edge
		int on = ((x / 16) + (y / 16)) & 1;
		float e = noise(x, y, 8, 7) * 0.15f;
		r = on ? 0.95f - e : 0.25f + e; g = on ? 0.80f - e : 0.30f + e; b = on ? 0.35f : 0.55f;
		break; }
	case 1: { // diagonal stripes
		float s = 0.5f + 0.5f * sinf((x + y) * 6.2831853f / 32.0f);
		r = 0.2f + 0.6f * s; g = 0.75f - 0.4f * s; b = 0.9f - 0.3f * s;
		break; }
	case 2: { // rings round the centre
		float dx = x - TEX_SIZE / 2.0f, dy = y - TEX_SIZE / 2.0f;
		float s = 0.5f + 0.5f * cosf(sqrtf(dx * dx + dy * dy) * 6.2831853f / 20.0f);
		r = 0.9f * s + 0.1f; g = 0.35f + 0.3f * s; b = 0.2f + 0.2f * (1.0f - s);
		break; }
	default: { // "stone": two octaves of value noise
		float v = 0.65f * noise(x, y, 32, 11) + 0.35f * noise(x, y, 8, 23);
		r = 0.3f + 0.5f * v; g = 0.35f + 0.5f * v; b = 0.3f + 0.4f * v;
		break; }
	}
	u32 R = (u32)(r * 255.0f) & 0xFF, G = (u32)(g * 255.0f) & 0xFF, B = (u32)(b * 255.0f) & 0xFF;
	return (R << 24) | (G << 16) | (B << 8) | 0xFF;
}

/* Interleaves the low 3 bits of x and y (the order of texels inside the GPU's 8 x 8 tiles). */
static u32 morton(u32 x, u32 y)
{
	u32 m = 0;
	for (int i = 0; i < 3; i++) m |= ((x >> i) & 1) << (2 * i) | ((y >> i) & 1) << (2 * i + 1);
	return m;
}

/* Copies a w x w picture (row 0 at the top) into [out], tiled the GPU's way (row 0 at the bottom). */
static void tile(const u32* in, u32* out, int w)
{
	for (int y = 0; y < w; y++)
		for (int x = 0; x < w; x++)
		{
			u32 ty = w - 1 - y;
			out[((ty >> 3) * (w >> 3) + (x >> 3)) * 64 + morton(x & 7, ty & 7)] = in[y * w + x];
		}
}

/* Makes the four textures and their mipmaps (down to 8 x 8, the smallest tile), all on the processor. */
static void texturesInit(void)
{
	static u32 pic[TEX_SIZE * TEX_SIZE], half[TEX_SIZE * TEX_SIZE / 4];
	u32* buf = linearAlloc(TEX_SIZE * TEX_SIZE * 4);
	const int levels = C3D_TexCalcMaxLevel(TEX_SIZE, TEX_SIZE) + 1;
	for (int k = 0; k < TEX_COUNT; k++)
	{
		for (int y = 0; y < TEX_SIZE; y++)
			for (int x = 0; x < TEX_SIZE; x++) pic[y * TEX_SIZE + x] = texel(k, x, y);
		C3D_TexInitMipmap(&tex[k], TEX_SIZE, TEX_SIZE, GPU_RGBA8); // levels 128 .. 8
		int w = TEX_SIZE;
		for (int level = 0; level < levels; level++)
		{
			tile(pic, buf, w);
			C3D_TexLoadImage(&tex[k], buf, GPU_TEXFACE_2D, level);
			// Next level: each texel is the average of a 2 x 2 block.
			int h = w / 2;
			for (int y = 0; y < h; y++)
				for (int x = 0; x < h; x++)
				{
					u32 s[4] = { pic[2 * y * w + 2 * x], pic[2 * y * w + 2 * x + 1], pic[(2 * y + 1) * w + 2 * x], pic[(2 * y + 1) * w + 2 * x + 1] };
					u32 v = 0;
					for (int c = 8; c < 32; c += 8)
						v |= (((s[0] >> c & 0xFF) + (s[1] >> c & 0xFF) + (s[2] >> c & 0xFF) + (s[3] >> c & 0xFF)) / 4) << c;
					half[y * h + x] = v | 0xFF;
				}
			memcpy(pic, half, h * h * 4);
			w = h;
		}
		C3D_TexFlush(&tex[k]);
		C3D_TexSetFilter(&tex[k], GPU_LINEAR, GPU_LINEAR);
		C3D_TexSetFilterMipmap(&tex[k], GPU_LINEAR);
		C3D_TexSetWrap(&tex[k], GPU_REPEAT, GPU_REPEAT);
	}
	linearFree(buf);
}

/* Physics-style processor load: a small particle system stepped [cpu] times a frame. */
static float px[64], py[64], vx[64], vy[64];
static void cpuWork(void)
{
	for (int n = 0; n < cpu; n++)
	{
		int i = n & 63;
		vy[i] -= 0.0005f;
		px[i] += vx[i]; py[i] += vy[i];
		if (py[i] < -1.0f) { py[i] = -1.0f; vy[i] = -vy[i] * 0.9f; }
		if (px[i] < -1.0f || px[i] > 1.0f) vx[i] = -vx[i];
		vx[i] += sinf(py[i] * 3.0f) * 0.0001f;
	}
}

static void readLoad(void)
{
	FILE* f = fopen("sdmc:/shoalcade/check.txt", "r");
	if (f)
	{
		int c = 0, p = 0;
		if (fscanf(f, "%d %d", &c, &p) == 2)
		{
			if (c > 0 && c <= MAX_CUBES) cubes = c;
			if (p >= 0 && p <= 10000000) cpu = p;
		}
		fclose(f);
	}
	for (int i = 0; i < 64; i++) { px[i] = (i % 8) / 4.0f - 1.0f; py[i] = (i / 8) / 4.0f; vx[i] = 0.003f * ((i % 3) - 1); vy[i] = 0.0f; }
}

static void sceneInit(void)
{
	vshader_dvlb = DVLB_ParseFile((u32*)vshader_shbin, vshader_shbin_size);
	shaderProgramInit(&program);
	shaderProgramSetVsh(&program, &vshader_dvlb->DVLE[0]);
	C3D_BindProgram(&program);
	uLoc_projection = shaderInstanceGetUniformLocation(program.vertexShader, "projection");
	uLoc_modelView  = shaderInstanceGetUniformLocation(program.vertexShader, "modelView");

	C3D_AttrInfo* attrInfo = C3D_GetAttrInfo();
	AttrInfo_Init(attrInfo);
	AttrInfo_AddLoader(attrInfo, 0, GPU_FLOAT, 3);
	AttrInfo_AddLoader(attrInfo, 1, GPU_FLOAT, 2);
	AttrInfo_AddLoader(attrInfo, 2, GPU_FLOAT, 3);

	vbo_data = linearAlloc(sizeof(vertex_list));
	memcpy(vbo_data, vertex_list, sizeof(vertex_list));
	C3D_BufInfo* bufInfo = C3D_GetBufInfo();
	BufInfo_Init(bufInfo);
	BufInfo_Add(bufInfo, vbo_data, sizeof(vertex), 3, 0x210);

	// Texture x diffuse light, then + specular light.
	C3D_TexEnv* env = C3D_GetTexEnv(0);
	C3D_TexEnvInit(env);
	C3D_TexEnvSrc(env, C3D_Both, GPU_TEXTURE0, GPU_FRAGMENT_PRIMARY_COLOR, 0);
	C3D_TexEnvFunc(env, C3D_Both, GPU_MODULATE);
	env = C3D_GetTexEnv(1);
	C3D_TexEnvInit(env);
	C3D_TexEnvSrc(env, C3D_Both, GPU_PREVIOUS, GPU_FRAGMENT_SECONDARY_COLOR, 0);
	C3D_TexEnvFunc(env, C3D_Both, GPU_ADD);
	texturesInit();

	static const C3D_Material material =
	{
		{ 0.25f, 0.25f, 0.25f }, { 0.8f, 0.8f, 0.8f }, { 0.5f, 0.5f, 0.5f }, { 0.0f, 0.0f, 0.0f }, { 0.0f, 0.0f, 0.0f },
	};
	C3D_LightEnvInit(&lightEnv);
	C3D_LightEnvBind(&lightEnv);
	C3D_LightEnvMaterial(&lightEnv, &material);
	LightLut_Phong(&lut_Phong, 30);
	C3D_LightEnvLut(&lightEnv, GPU_LUT_D0, GPU_LUTINPUT_LN, false, &lut_Phong);
	C3D_FVec lightVec = FVec4_New(0.0f, 0.0f, -0.5f, 1.0f);
	C3D_LightInit(&light, &lightEnv);
	C3D_LightColor(&light, 1.0, 1.0, 1.0);
	C3D_LightPosition(&light, &lightVec);
}

/* One frame: a square grid of [cubes] cubes, each spinning its own way, one draw call each. */
static void sceneRender(void)
{
	Mtx_PerspTilt(&projection, C3D_AngleFromDegrees(60.0f), C3D_AspectRatioTop, 0.01f, 1000.0f, false);
	C3D_FVUnifMtx4x4(GPU_VERTEX_SHADER, uLoc_projection, &projection);
	int side = (int)ceilf(sqrtf((float)cubes));
	float spacing = 6.0f / side;
	for (int i = 0; i < cubes; i++)
	{
		int gx = i % side, gy = i / side;
		C3D_Mtx modelView;
		Mtx_Identity(&modelView);
		Mtx_Translate(&modelView, (gx - side / 2.0f) * spacing, (gy - side / 2.0f) * spacing * 0.6f, -6.0f, true);
		Mtx_RotateX(&modelView, angle + i * 0.1f, true);
		Mtx_RotateY(&modelView, angle * 0.5f + i * 0.07f, true);
		Mtx_Scale(&modelView, spacing * 0.6f, spacing * 0.6f, spacing * 0.6f);
		C3D_FVUnifMtx4x4(GPU_VERTEX_SHADER, uLoc_modelView, &modelView);
		C3D_TexBind(0, &tex[i % TEX_COUNT]);
		C3D_DrawArrays(GPU_TRIANGLES, 0, vertex_list_count);
	}
	angle += C3D_AngleFromDegrees(1.0f);
}

int main()
{
	gfxInitDefault();
	readLoad();
	consoleInit(GFX_BOTTOM, NULL);
	printf("\n  Shoalcade 3DS check\n\n  Testing how fast this device\n  runs 3DS games.\n\n  %d textured cubes, load %d\n", cubes, cpu);
	C3D_Init(C3D_DEFAULT_CMDBUF_SIZE * 8);

	C3D_RenderTarget* target = C3D_RenderTargetCreate(240, 400, GPU_RB_RGBA8, GPU_RB_DEPTH24_STENCIL8);
	C3D_RenderTargetSetOutput(target, GFX_TOP, GFX_LEFT, DISPLAY_TRANSFER_FLAGS);
	sceneInit();

	while (aptMainLoop())
	{
		hidScanInput();
		cpuWork();
		C3D_FrameBegin(C3D_FRAME_SYNCDRAW);
		C3D_RenderTargetClear(target, C3D_CLEAR_ALL, CLEAR_COLOR, 0);
		C3D_FrameDrawOn(target);
		sceneRender();
		C3D_FrameEnd(0);
	}

	for (int k = 0; k < TEX_COUNT; k++) C3D_TexDelete(&tex[k]);
	linearFree(vbo_data);
	shaderProgramFree(&program);
	DVLB_Free(vshader_dvlb);
	C3D_Fini();
	gfxExit();
	return 0;
}
