#include "compositor.h"
#include "log.h"
#include "sheets_gen.h"
#include <windows.h>
#include <d3d9.h>
#include <d3dcompiler.h>
#include <cstring>
#include <string>

namespace
{
// FrameExporter.java's layout
constexpr int kMagic = 0x5450434D;  // "MCPT"
constexpr size_t kHeader = 4096;
constexpr size_t kSlotDesc = 256, kSlotDescBytes = 128;
// slot flags: 1 depth in [0,1], 2 rows bottom-up, 4 reversed Z (the shader assumes it), 8 show the overlay
constexpr int kFlagBottomUp = 2, kFlagOverlay = 8;

HANDLE g_mapping = nullptr;
const uint8_t *g_header = nullptr;
int64_t g_lastPublish = -1;

IDirect3DDevice9 *g_device = nullptr;
IDirect3DTexture9 *g_color = nullptr, *g_depth = nullptr, *g_overlay = nullptr;
int g_texW = 0, g_texH = 0;
IDirect3DVertexShader9 *g_vs = nullptr;
IDirect3DPixelShader9 *g_psWorld = nullptr, *g_psOverlay = nullptr;
IDirect3DVertexDeclaration9 *g_decl = nullptr;
bool g_failed = false;
std::string g_status;

float g_hostNear = 10.0f, g_hostFar = 300000.0f;
float g_mcNear = 0.05f, g_mcFar = 1024.0f;
int g_flags = 0;
bool g_haveFrame = false;
long long g_draws = 0;

const char *kShader = R"(
sampler2D sColor : register(s0);
sampler2D sDepth : register(s1);
sampler2D sOverlay : register(s2);
float4 planes : register(c0);   // Minecraft near, far (blocks); New Vegas near, far (units)
float4 opts : register(c1);     // x unused, y units per block, z depth bias (units), w depth test
float4 halfPixel : register(c2);

struct VsOut { float4 pos : POSITION; float2 uv : TEXCOORD0; };
VsOut vs_main(float2 p : POSITION)
{
	VsOut o;
	o.pos = float4(p.x - halfPixel.x, p.y + halfPixel.y, 0, 1);
	o.uv = float2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5);
	return o;
}

float2 src(float2 uv) { return uv; }  // rows are already top-down (flipped on upload)

struct PsOut { float4 c : COLOR0; float d : DEPTH; };
PsOut ps_world(float2 uv : TEXCOORD0)
{
	float2 t = src(uv);
	float4 c = tex2D(sColor, t).bgra;   // Minecraft's RGBA bytes in an A8R8G8B8 texture
	clip(c.a - 0.5 / 255.0);
	float d = tex2D(sDepth, t).r;       // reversed Z: 1 near, 0 far
	float zmc = planes.x * planes.y / (planes.x + max(d, 1e-7) * (planes.y - planes.x));
	float z = max(zmc * opts.y - opts.z, planes.z);
	float n = planes.z, f = planes.w;
	PsOut o;
	o.c = c;
	o.d = saturate((f / (f - n)) * (1.0 - n / z)) * opts.w;  // opts.w 0: depth test off (drawn at the near plane)
	return o;
}

float4 ps_overlay(float2 uv : TEXCOORD0) : COLOR0
{
	float4 c = tex2D(sOverlay, src(uv)).bgra;
	clip(c.a - 0.5 / 255.0);
	return c;
}
)";

typedef HRESULT(WINAPI *D3DCompile_t)(LPCVOID, SIZE_T, LPCSTR, const D3D_SHADER_MACRO *, ID3DInclude *, LPCSTR, LPCSTR, UINT, UINT,
	ID3DBlob **, ID3DBlob **);

void Fail(const std::string &why)
{
	g_failed = true;
	g_status = why;
	logf("compositor disabled: %s", why.c_str());
}

void ReleaseTextures()
{
	for (IDirect3DTexture9 **t : {&g_color, &g_depth, &g_overlay})
		if (*t)
		{
			(*t)->Release();
			*t = nullptr;
		}
	g_texW = g_texH = 0;
}

void ReleaseAll()
{
	ReleaseTextures();
	if (g_vs) g_vs->Release();
	if (g_psWorld) g_psWorld->Release();
	if (g_psOverlay) g_psOverlay->Release();
	if (g_decl) g_decl->Release();
	g_vs = nullptr;
	g_psWorld = g_psOverlay = nullptr;
	g_decl = nullptr;
	g_device = nullptr;
}

bool CompileShaders(IDirect3DDevice9 *device)
{
	HMODULE dll = LoadLibraryA("d3dcompiler_47.dll");
	if (!dll)
		dll = LoadLibraryA("d3dcompiler_43.dll");
	auto compile = dll ? reinterpret_cast<D3DCompile_t>(GetProcAddress(dll, "D3DCompile")) : nullptr;
	if (!compile)
	{
		Fail("d3dcompiler_47.dll not found");
		return false;
	}
	auto build = [&](const char *entry, const char *target, ID3DBlob **out) {
		ID3DBlob *errors = nullptr;
		HRESULT hr = compile(kShader, strlen(kShader), "osl", nullptr, nullptr, entry, target, 0, 0, out, &errors);
		if (FAILED(hr))
		{
			Fail(std::string("shader ") + entry + ": " + (errors ? static_cast<const char *>(errors->GetBufferPointer()) : "?"));
			if (errors) errors->Release();
			return false;
		}
		return true;
	};
	ID3DBlob *vs = nullptr, *psw = nullptr, *pso = nullptr;
	bool ok = build("vs_main", "vs_3_0", &vs) && build("ps_world", "ps_3_0", &psw) && build("ps_overlay", "ps_3_0", &pso);
	if (ok)
		ok = SUCCEEDED(device->CreateVertexShader(static_cast<const DWORD *>(vs->GetBufferPointer()), &g_vs))
			&& SUCCEEDED(device->CreatePixelShader(static_cast<const DWORD *>(psw->GetBufferPointer()), &g_psWorld))
			&& SUCCEEDED(device->CreatePixelShader(static_cast<const DWORD *>(pso->GetBufferPointer()), &g_psOverlay));
	for (ID3DBlob *b : {vs, psw, pso})
		if (b) b->Release();
	D3DVERTEXELEMENT9 elements[] = {{0, 0, D3DDECLTYPE_FLOAT2, D3DDECLMETHOD_DEFAULT, D3DDECLUSAGE_POSITION, 0}, D3DDECL_END()};
	if (ok)
		ok = SUCCEEDED(device->CreateVertexDeclaration(elements, &g_decl));
	if (!ok && !g_failed)
		Fail("creating shaders failed");
	return ok;
}

bool OpenShared()
{
	if (g_header)
		return true;
	g_mapping = OpenFileMappingA(FILE_MAP_READ, FALSE, sheets::FRAME_SHM);
	if (!g_mapping)
		return false;
	g_header = static_cast<const uint8_t *>(MapViewOfFile(g_mapping, FILE_MAP_READ, 0, 0, kHeader));
	if (!g_header || *reinterpret_cast<const int *>(g_header) != kMagic)
	{
		if (g_header) UnmapViewOfFile(g_header);
		CloseHandle(g_mapping);
		g_header = nullptr;
		g_mapping = nullptr;
		return false;
	}
	logf("compositor: Minecraft frames found (%s)", sheets::FRAME_SHM);
	return true;
}

template <typename T>
T At(const uint8_t *base, size_t offset)
{
	T v;
	std::memcpy(&v, base + offset, sizeof v);
	return v;
}

bool EnsureTextures(IDirect3DDevice9 *device, int w, int h)
{
	if (g_color && g_texW == w && g_texH == h)
		return true;
	ReleaseTextures();
	if (FAILED(device->CreateTexture(w, h, 1, D3DUSAGE_DYNAMIC, D3DFMT_A8R8G8B8, D3DPOOL_DEFAULT, &g_color, nullptr))
		|| FAILED(device->CreateTexture(w, h, 1, D3DUSAGE_DYNAMIC, D3DFMT_R32F, D3DPOOL_DEFAULT, &g_depth, nullptr))
		|| FAILED(device->CreateTexture(w, h, 1, D3DUSAGE_DYNAMIC, D3DFMT_A8R8G8B8, D3DPOOL_DEFAULT, &g_overlay, nullptr)))
	{
		ReleaseTextures();
		return false;
	}
	g_texW = w;
	g_texH = h;
	return true;
}

/// Copies one layer; Minecraft's rows are bottom-up (GL), Direct3D's top-down: flipped here, not in the shader.
bool Upload(IDirect3DTexture9 *tex, const uint8_t *src, int w, int h, bool bottomUp)
{
	D3DLOCKED_RECT r;
	if (FAILED(tex->LockRect(0, &r, nullptr, D3DLOCK_DISCARD)))
		return false;
	const size_t row = size_t(w) * 4;
	for (int y = 0; y < h; y++)
		std::memcpy(static_cast<uint8_t *>(r.pBits) + size_t(y) * r.Pitch, src + size_t(bottomUp ? h - 1 - y : y) * row, row);
	tex->UnlockRect(0);
	return true;
}

/// Copy the newest published slot into the textures (only when Minecraft published a new one).
void Fetch(IDirect3DDevice9 *device)
{
	int64_t publish = At<int64_t>(g_header, 32);
	int slot = At<int>(g_header, 40);
	if (slot < 0 || publish == g_lastPublish)
		return;
	const size_t desc = kSlotDesc + kSlotDescBytes * slot;
	int64_t seq = At<int64_t>(g_header, desc);
	if (seq & 1)
		return;  // being written: next frame
	int w = At<int>(g_header, desc + 24), h = At<int>(g_header, desc + 28);
	if (w <= 0 || h <= 0 || w > 3840 || h > 2160)
		return;
	const int64_t stride = At<int64_t>(g_header, 16);
	const uint64_t start = kHeader + uint64_t(stride) * slot;
	SYSTEM_INFO si;
	GetSystemInfo(&si);
	const uint64_t aligned = start - start % si.dwAllocationGranularity;
	const size_t lead = size_t(start - aligned);
	const size_t layer = size_t(w) * h * 4;
	const uint8_t *view = static_cast<const uint8_t *>(
		MapViewOfFile(g_mapping, FILE_MAP_READ, DWORD(aligned >> 32), DWORD(aligned), lead + layer * 3));
	if (!view)
		return;
	const uint8_t *data = view + lead;
	if (EnsureTextures(device, w, h))
	{
		const bool up = (At<int>(g_header, desc + 44) & kFlagBottomUp) != 0;
		bool ok = Upload(g_color, data, w, h, up) && Upload(g_depth, data + layer, w, h, up) && Upload(g_overlay, data + 2 * layer, w, h, up);
		// the slot must not have been rewritten while copying
		if (ok && At<int64_t>(g_header, desc) == seq)
		{
			g_mcNear = At<float>(g_header, desc + 32);
			g_mcFar = At<float>(g_header, desc + 36);
			g_flags = At<int>(g_header, desc + 44);
			g_lastPublish = publish;
			g_haveFrame = true;
		}
	}
	UnmapViewOfFile(view);
}

void Draw(IDirect3DDevice9 *device, const compositor::Settings &s)
{
	IDirect3DStateBlock9 *saved = nullptr;
	if (FAILED(device->CreateStateBlock(D3DSBT_ALL, &saved)))
		return;
	IDirect3DSurface9 *backBuffer = nullptr, *oldRt = nullptr;
	device->GetRenderTarget(0, &oldRt);
	device->GetBackBuffer(0, 0, D3DBACKBUFFER_TYPE_MONO, &backBuffer);
	D3DSURFACE_DESC bb{};
	if (backBuffer)
	{
		backBuffer->GetDesc(&bb);
		device->SetRenderTarget(0, backBuffer);
	}

	// does the bound depth surface match the back buffer? Otherwise the GPU can't test against it.
	bool depthOk = false;
	IDirect3DSurface9 *ds = nullptr;
	if (s.depthTest && SUCCEEDED(device->GetDepthStencilSurface(&ds)) && ds)
	{
		D3DSURFACE_DESC dd{};
		ds->GetDesc(&dd);
		depthOk = dd.Width >= bb.Width && dd.Height >= bb.Height && dd.MultiSampleType == bb.MultiSampleType;
		ds->Release();
	}

	device->BeginScene();
	D3DVIEWPORT9 vp = {0, 0, bb.Width, bb.Height, 0.0f, 1.0f};
	device->SetViewport(&vp);
	device->SetVertexDeclaration(g_decl);
	device->SetVertexShader(g_vs);
	device->SetRenderState(D3DRS_CULLMODE, D3DCULL_NONE);
	device->SetRenderState(D3DRS_ALPHABLENDENABLE, TRUE);
	device->SetRenderState(D3DRS_SRCBLEND, D3DBLEND_ONE);  // premultiplied alpha
	device->SetRenderState(D3DRS_DESTBLEND, D3DBLEND_INVSRCALPHA);
	device->SetRenderState(D3DRS_BLENDOP, D3DBLENDOP_ADD);
	device->SetRenderState(D3DRS_ALPHATESTENABLE, FALSE);
	device->SetRenderState(D3DRS_STENCILENABLE, FALSE);
	device->SetRenderState(D3DRS_SCISSORTESTENABLE, FALSE);
	device->SetRenderState(D3DRS_FOGENABLE, FALSE);
	device->SetRenderState(D3DRS_SRGBWRITEENABLE, FALSE);
	device->SetRenderState(D3DRS_COLORWRITEENABLE, 0xF);
	for (DWORD i = 0; i < 3; i++)
	{
		device->SetSamplerState(i, D3DSAMP_MINFILTER, D3DTEXF_POINT);
		device->SetSamplerState(i, D3DSAMP_MAGFILTER, D3DTEXF_POINT);
		device->SetSamplerState(i, D3DSAMP_MIPFILTER, D3DTEXF_NONE);
		device->SetSamplerState(i, D3DSAMP_ADDRESSU, D3DTADDRESS_CLAMP);
		device->SetSamplerState(i, D3DSAMP_ADDRESSV, D3DTADDRESS_CLAMP);
		device->SetSamplerState(i, D3DSAMP_SRGBTEXTURE, FALSE);
	}
	device->SetTexture(0, g_color);
	device->SetTexture(1, g_depth);
	device->SetTexture(2, g_overlay);

	float planes[4] = {g_mcNear, g_mcFar, g_hostNear, g_hostFar};
	float opts[4] = {0.0f, sheets::UNITS_PER_BLOCK, s.depthBias, depthOk ? 1.0f : 0.0f};
	float half[4] = {1.0f / float(bb.Width ? bb.Width : 1), 1.0f / float(bb.Height ? bb.Height : 1), 0, 0};
	device->SetPixelShaderConstantF(0, planes, 1);
	device->SetPixelShaderConstantF(1, opts, 1);
	device->SetVertexShaderConstantF(2, half, 1);
	const float quad[] = {-1, -1, -1, 1, 1, -1, 1, 1};

	// world layer: depth-tested, never writing depth
	device->SetRenderState(D3DRS_ZENABLE, depthOk ? D3DZB_TRUE : D3DZB_FALSE);
	device->SetRenderState(D3DRS_ZFUNC, D3DCMP_LESSEQUAL);
	device->SetRenderState(D3DRS_ZWRITEENABLE, FALSE);
	device->SetPixelShader(g_psWorld);
	device->DrawPrimitiveUP(D3DPT_TRIANGLESTRIP, 2, quad, sizeof(float) * 2);

	// overlay: hand, hotbar, Minecraft screens
	if (g_flags & kFlagOverlay)
	{
		device->SetRenderState(D3DRS_ZENABLE, D3DZB_FALSE);
		device->SetPixelShader(g_psOverlay);
		device->DrawPrimitiveUP(D3DPT_TRIANGLESTRIP, 2, quad, sizeof(float) * 2);
	}
	device->EndScene();

	if (oldRt)
	{
		device->SetRenderTarget(0, oldRt);
		oldRt->Release();
	}
	if (backBuffer)
		backBuffer->Release();
	saved->Apply();
	saved->Release();
	static bool loggedDepth = false;
	if (!loggedDepth)
	{
		loggedDepth = true;
		logf("compositor: back buffer %ux%u, depth test %s", bb.Width, bb.Height, depthOk ? "on" : "off (depth surface doesn't match)");
	}
}
}  // namespace

namespace compositor
{
void SetHostPlanes(float nearPlane, float farPlane)
{
	g_hostNear = nearPlane;
	g_hostFar = farPlane;
}

void Present(IDirect3DDevice9 *device, bool show, const Settings &settings)
{
	if (g_failed || !device || !OpenShared())
		return;
	if (device != g_device)
	{
		ReleaseAll();
		g_device = device;
		if (!CompileShaders(device))
			return;
	}
	if (device->TestCooperativeLevel() != D3D_OK)
	{
		ReleaseTextures();  // lost: D3DPOOL_DEFAULT textures must go before the game resets the device
		g_haveFrame = false;
		return;
	}
	Fetch(device);
	if (show && g_haveFrame)
	{
		Draw(device, settings);
		g_draws++;
	}
}

void Shutdown()
{
	ReleaseAll();
	if (g_header) UnmapViewOfFile(g_header);
	if (g_mapping) CloseHandle(g_mapping);
	g_header = nullptr;
	g_mapping = nullptr;
}

long long Draws()
{
	return g_draws;
}

bool SharedOpen()
{
	return g_header != nullptr;
}

const char *Status()
{
	return g_status.c_str();
}
}  // namespace compositor
