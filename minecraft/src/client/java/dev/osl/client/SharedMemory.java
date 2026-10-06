package dev.osl.client;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/** A named, pagefile-backed Win32 file mapping: other processes open it by name, and nothing touches the disk (Linux: /dev/shm, for tests). */
final class SharedMemory {
	private static final int PAGE_READWRITE = 0x04;
	private static final int FILE_MAP_ALL_ACCESS = 0xF001F;
	final MemorySegment segment;

	private SharedMemory(final MemorySegment segment) {
		this.segment = segment;
	}

	static SharedMemory create(final String name, final long size) throws Throwable {
		if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
			return createFile(name, size);
		}

		Linker linker = Linker.nativeLinker();
		SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
		MethodHandle createFileMapping = linker.downcallHandle(
			kernel32.find("CreateFileMappingW").orElseThrow(),
			FunctionDescriptor.of(
				ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS
			)
		);
		MethodHandle mapViewOfFile = linker.downcallHandle(
			kernel32.find("MapViewOfFile").orElseThrow(),
			FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
		);
		MemorySegment wideName = Arena.global().allocateFrom(ValueLayout.JAVA_BYTE, (name + "\0").getBytes(StandardCharsets.UTF_16LE));
		MemorySegment invalidHandle = MemorySegment.ofAddress(-1L);
		MemorySegment handle = (MemorySegment)createFileMapping.invoke(
			invalidHandle, MemorySegment.NULL, PAGE_READWRITE, (int)(size >>> 32), (int)size, wideName
		);
		if (handle.address() == 0L) {
			throw new IllegalStateException("CreateFileMappingW failed for " + name);
		}

		MemorySegment view = (MemorySegment)mapViewOfFile.invoke(handle, FILE_MAP_ALL_ACCESS, 0, 0, size);
		if (view.address() == 0L) {
			throw new IllegalStateException("MapViewOfFile failed for " + name);
		}

		return new SharedMemory(view.reinterpret(size));
	}

	/** Development on Linux (tests, the fake New Vegas): the same layout in /dev/shm/<name without "Local\\">. */
	private static SharedMemory createFile(final String name, final long size) throws java.io.IOException {
		java.nio.file.Path path = java.nio.file.Path.of("/dev/shm", name.substring(name.lastIndexOf('\\') + 1));
		try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.CREATE,
			java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)) {
			return new SharedMemory(ch.map(java.nio.channels.FileChannel.MapMode.READ_WRITE, 0L, size, Arena.global()));
		}
	}
}
