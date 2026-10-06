// The parts of xNVSE's plugin API (nvse/PluginAPI.h, xNVSE 6.4.x) this plugin uses, declared with the same layout.
// Kept minimal so the plugin builds without the xNVSE source tree.
#pragma once
#include <cstdint>

using UInt8 = uint8_t;
using UInt16 = uint16_t;
using UInt32 = uint32_t;
using PluginHandle = UInt32;

struct TESForm;
struct TESObjectREFR;
struct Script;

enum : UInt32
{
	kInterface_Serialization = 0,
	kInterface_Console,
	kInterface_Messaging,
	kInterface_CommandTable,
	kInterface_StringVar,
	kInterface_ArrayVar,
	kInterface_Script,
	kInterface_Data,
	kInterface_EventManager,
	kInterface_Logging,
	kInterface_PlayerControls,
};

struct PluginInfo
{
	enum { kInfoVersion = 1 };
	UInt32 infoVersion;
	const char *name;
	UInt32 version;
};

struct NVSEInterface
{
	UInt32 nvseVersion;
	UInt32 runtimeVersion;
	UInt32 editorVersion;
	UInt32 isEditor;
	bool (*RegisterCommand)(void *info);
	void (*SetOpcodeBase)(UInt32 opcode);
	void *(*QueryInterface)(UInt32 id);
	PluginHandle (*GetPluginHandle)(void);
	// (more members follow in xNVSE; not used)
};

struct NVSEConsoleInterface
{
	UInt32 version;
	bool (*RunScriptLine)(const char *buf, TESObjectREFR *object);
	bool (*RunScriptLine2)(const char *buf, TESObjectREFR *callingRefr, bool bSuppressConsoleOutput);
};

struct NVSEMessagingInterface
{
	struct Message
	{
		const char *sender;
		UInt32 type;
		UInt32 dataLen;
		void *data;
	};
	typedef void (*EventCallback)(Message *msg);

	enum
	{
		kMessage_PostLoad,
		kMessage_ExitGame,
		kMessage_ExitToMainMenu,
		kMessage_LoadGame,
		kMessage_SaveGame,
		kMessage_ScriptPrecompile,
		kMessage_PreLoadGame,
		kMessage_ExitGame_Console,
		kMessage_PostLoadGame,
		kMessage_PostPostLoad,
		kMessage_RuntimeScriptError,
		kMessage_DeleteGame,
		kMessage_RenameGame,
		kMessage_RenameNewGame,
		kMessage_NewGame,
		kMessage_DeleteGameName,
		kMessage_RenameGameName,
		kMessage_RenameNewGameName,
		kMessage_DeferredInit,
		kMessage_ClearScriptDataCache,
		kMessage_MainGameLoop,
		kMessage_ScriptCompile,
		kMessage_EventListDestroyed,
		kMessage_PostQueryPlugins,
		kMessage_OnFramePresent,
	};

	UInt32 version;
	bool (*RegisterListener)(PluginHandle listener, const char *sender, EventCallback handler);
	bool (*Dispatch)(PluginHandle sender, UInt32 messageType, void *data, UInt32 dataLen, const char *receiver);
};

// NVSEArrayVarInterface::Element: a union and a type byte.
struct NVSEElement
{
	union
	{
		char *str;
		void *arr;
		TESForm *form;
		double num;
	};
	UInt8 type;  // 1 numeric, 2 form, 3 string, 4 array
};

struct NVSEScriptInterface
{
	bool (*CallFunction)(Script *funcScript, TESObjectREFR *callingObj, TESObjectREFR *container, NVSEElement *result, UInt8 numArgs, ...);
	UInt32 (*GetFunctionParams)(Script *funcScript, UInt8 *paramTypesOut);
	void *ExtractArgsEx;
	void *ExtractFormatStringArgs;
	bool (*CallFunctionAlt)(Script *funcScript, TESObjectREFR *callingObj, UInt8 numArgs, ...);
	Script *(*CompileScript)(const char *scriptText);
	Script *(*CompileExpression)(const char *expression);
};

struct NVSESerializationInterface
{
	typedef void (*EventCallback)(void *reserved);
	UInt32 version;
	void (*SetSaveCallback)(PluginHandle plugin, EventCallback callback);
	void (*SetLoadCallback)(PluginHandle plugin, EventCallback callback);
	void (*SetNewGameCallback)(PluginHandle plugin, EventCallback callback);
	bool (*WriteRecord)(UInt32 type, UInt32 version, const void *buf, UInt32 length);
	bool (*OpenRecord)(UInt32 type, UInt32 version);
	bool (*WriteRecordData)(const void *buf, UInt32 length);
	bool (*GetNextRecordInfo)(UInt32 *type, UInt32 *version, UInt32 *length);
	UInt32 (*ReadRecordData)(void *buf, UInt32 length);
};

struct NVSETogglePlayerControlsInterface
{
	enum : UInt32
	{
		kFlag_Movement = 1 << 0,
		kFlag_Looking = 1 << 1,
		kFlag_Pipboy = 1 << 2,
		kFlag_Fighting = 1 << 3,
		kFlag_POV = 1 << 4,
		kFlag_RolloverText = 1 << 5,
		kFlag_Sneaking = 1 << 6,
		kFlag_Attacking = 1 << 7,
		kFlag_EnterVATS = 1 << 8,
		kFlag_Jumping = 1 << 9,
		kFlag_AimingOrBlocking = 1 << 10,
	};
	void(__fastcall *DisablePlayerControlsAlt)(UInt32 flagsToAdd, const char *modName);
	void(__fastcall *EnablePlayerControlsAlt)(UInt32 flagsToRemove, const char *modName);
};

constexpr UInt32 RUNTIME_VERSION_1_4_0_525 = 0x040020D0;  // xNVSE's RUNTIME_VERSION_1_4_0_525
