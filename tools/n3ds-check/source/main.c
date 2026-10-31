/*
 * Shoalcade 3DS check scene (plan-3ds step 7, D151). Built on devkitPro's 3ds-examples
 * graphics/gpu/fragment_light (Public Domain Mark). Shoalcade's changes are GPL-3.0-or-later.
 *
 * A fixed, repeatable load for the app's "3DS check": a grid of lit, spinning cubes (one draw call each,
 * like a real game's many objects) plus a physics-style processor loop. The app measures how fast the
 * emulator runs it in real time; nothing here times itself (the emulated clock slows down with the emulator).
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

	C3D_TexEnv* env = C3D_GetTexEnv(0);
	C3D_TexEnvInit(env);
	C3D_TexEnvSrc(env, C3D_Both, GPU_FRAGMENT_PRIMARY_COLOR, GPU_FRAGMENT_SECONDARY_COLOR, 0);
	C3D_TexEnvFunc(env, C3D_Both, GPU_ADD);

	static const C3D_Material material =
	{
		{ 0.2f, 0.2f, 0.25f }, { 0.3f, 0.5f, 0.8f }, { 0.8f, 0.8f, 0.8f }, { 0.0f, 0.0f, 0.0f }, { 0.0f, 0.0f, 0.0f },
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
		C3D_DrawArrays(GPU_TRIANGLES, 0, vertex_list_count);
	}
	angle += C3D_AngleFromDegrees(1.0f);
}

int main()
{
	gfxInitDefault();
	readLoad();
	consoleInit(GFX_BOTTOM, NULL);
	printf("\n  Shoalcade 3DS check\n\n  Testing how fast this phone\n  runs 3DS games.\n\n  %d cubes, load %d\n", cubes, cpu);
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

	linearFree(vbo_data);
	shaderProgramFree(&program);
	DVLB_Free(vshader_dvlb);
	C3D_Fini();
	gfxExit();
	return 0;
}
