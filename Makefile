#---------------------------------------------------------------------------------
.SUFFIXES:
#---------------------------------------------------------------------------------

ifeq ($(strip $(DEVKITARM)),)
$(error "Please set DEVKITARM in your environment. export DEVKITARM=<path to>devkitARM")
endif

TOPDIR ?= $(CURDIR)
include $(DEVKITARM)/3ds_rules

TARGET          := min3dstry
BUILD           := build/3ds
SOURCES         := src/render src/sim src/net src/audio src/ui src/platform src/platform/3ds
DATA            :=
INCLUDES        := include
GRAPHICS        :=
ROMFS           := build/romfs
ATLAS           := build/atlas
AUDIO_ROMFS     := build/audio-romfs
BUILD_CONFIG    := $(BUILD)/build-config.h
BUILD_INFO      := $(BUILD)/build-info.h
UPSTREAM_MINDUSTRY ?=
TEAVM_LOADER_DIR ?=
PYTHON          ?= python3
TEX3DS          ?= tex3ds

ifneq ($(strip $(UPSTREAM_MINDUSTRY)),)
M3DS_AUDIO_ROMFS_VALUE := 1
else
M3DS_AUDIO_ROMFS_VALUE := 0
endif

ifneq ($(strip $(TEAVM_LOADER_DIR)),)
M3DS_TEAVM_LOADER_VALUE := 1
else
M3DS_TEAVM_LOADER_VALUE := 0
endif

APP_TITLE       := Min3DStry Citro2D
APP_DESCRIPTION := Bounded Citro2D rendering prototype
APP_AUTHOR      := Min3DStry Contributors

ARCH    := -march=armv6k -mtune=mpcore -mfloat-abi=hard -mtp=soft

CFLAGS  := -g -Wall -Wextra -Werror -O2 -std=c11 -mword-relocations \
           -ffunction-sections \
           $(ARCH)

CFLAGS  += $(INCLUDE) -D__3DS__

CXXFLAGS := $(CFLAGS) -fno-rtti -fno-exceptions -std=gnu++11

ASFLAGS := -g $(ARCH)
LDFLAGS  = -specs=3dsx.specs -g $(ARCH) -Wl,-Map,$(notdir $*.map)

LIBS    := -lcitro2d -lcitro3d -lvorbisidec -logg -lctru -lm

ifneq ($(strip $(TEAVM_LOADER_DIR)),)
override TEAVM_LOADER_DIR := $(abspath $(TEAVM_LOADER_DIR))
# The archive references libctru/libm, so it must be scanned before them.
LIBS    := -L$(TEAVM_LOADER_DIR) -lm3dsloader $(LIBS)
endif

LIBDIRS := $(PORTLIBS) $(CTRULIB)

ifneq ($(CURDIR),$(TOPDIR)/$(BUILD))

export OUTPUT := $(CURDIR)/build/$(TARGET)
export TOPDIR := $(CURDIR)
export TEAVM_LOADER_DIR

export VPATH := $(foreach dir,$(SOURCES),$(CURDIR)/$(dir)) \
                $(foreach dir,$(DATA),$(CURDIR)/$(dir))

export DEPSDIR := $(CURDIR)/$(BUILD)

CFILES   := $(foreach dir,$(SOURCES),$(notdir $(wildcard $(dir)/*.c)))
CPPFILES := $(foreach dir,$(SOURCES),$(notdir $(wildcard $(dir)/*.cpp)))
SFILES   := $(foreach dir,$(SOURCES),$(notdir $(wildcard $(dir)/*.s)))
BINFILES := $(foreach dir,$(DATA),$(notdir $(wildcard $(dir)/*.*)))

ifeq ($(strip $(CPPFILES)),)
	export LD := $(CC)
else
	export LD := $(CXX)
endif

export OFILES_SOURCES := $(CPPFILES:.cpp=.o) $(CFILES:.c=.o) $(SFILES:.s=.o)
export OFILES_BIN     := $(addsuffix .o,$(BINFILES))
export OFILES         := $(OFILES_BIN) $(OFILES_SOURCES)

export HFILES := $(addsuffix .h,$(subst .,_,$(BINFILES)))

export INCLUDE := $(foreach dir,$(INCLUDES),-I$(CURDIR)/$(dir)) \
                  $(foreach dir,$(LIBDIRS),-I$(dir)/include) \
                  -I$(CURDIR)/$(ATLAS) \
                  -I$(CURDIR)/$(BUILD)

export LIBPATHS := $(foreach dir,$(LIBDIRS),-L$(dir)/lib)

export _3DSXDEPS := $(if $(NO_SMDH),,$(OUTPUT).smdh) $(CURDIR)/$(ROMFS)/sprites.t3x
export _3DSXFLAGS += --romfs=$(CURDIR)/$(ROMFS)

ifeq ($(strip $(NO_SMDH)),)
export _3DSXFLAGS += --smdh=$(OUTPUT).smdh
endif

.PHONY: all clean audio-romfs romfs-audio-clean force-config

# CI builds the .3dsx target directly to avoid rerunning the ffmpeg-dependent
# audio target inside the devkitARM container. The stock 3ds_rules %.elf rule
# has no prerequisites, so make must be told to compile the objects first.
$(OUTPUT).elf: $(OFILES)

ifneq ($(strip $(UPSTREAM_MINDUSTRY)),)
all: audio-romfs
else
all: romfs-audio-clean
endif

all: $(BUILD_CONFIG) $(BUILD_INFO) $(ATLAS)/sprites.h $(ROMFS)/sprites.t3x
	@mkdir -p $(BUILD) $(dir $(OUTPUT))
	@$(MAKE) --no-print-directory -C $(BUILD) -f $(CURDIR)/Makefile

$(BUILD_CONFIG): force-config
	@mkdir -p $(BUILD)
	@printf '#define M3DS_AUDIO_ROMFS %s\n#define M3DS_TEAVM_LOADER_EMBEDDED %s\n' '$(M3DS_AUDIO_ROMFS_VALUE)' '$(M3DS_TEAVM_LOADER_VALUE)' > $@.tmp
	@if cmp -s $@.tmp $@; then rm -f $@.tmp; else mv $@.tmp $@; fi

force-config:

# Build provenance for hardware evidence. Kept out of build-config.h so a new
# commit only rebuilds main_3ds.o instead of every object. M3DS_BUILD_COMMIT is
# exported by dev.sh/CI; otherwise ask git, and fall back to "unknown".
$(BUILD_INFO): force-config
	@mkdir -p $(BUILD)
	@commit='$(M3DS_BUILD_COMMIT)'; \
	if [ "$$commit" = "unknown" ]; then commit=''; fi; \
	if [ -z "$$commit" ] && command -v git >/dev/null 2>&1; then \
	  commit="$$(git -C $(TOPDIR) rev-parse --short=12 HEAD 2>/dev/null || true)"; \
	fi; \
	if [ -z "$$commit" ]; then \
	  commit="$$(sh $(TOPDIR)/tools/git_head_sha.sh $(TOPDIR) 2>/dev/null || true)"; \
	fi; \
	if [ -z "$$commit" ]; then commit=unknown; fi; \
	dirty='$(M3DS_BUILD_DIRTY)'; \
	if [ -z "$$dirty" ]; then \
	  dirty=0; \
	  if [ "$$commit" != "unknown" ] && command -v git >/dev/null 2>&1 && \
	     ! git -C $(TOPDIR) diff --quiet --ignore-submodules HEAD 2>/dev/null; then \
	    dirty=1; \
	  fi; \
	fi; \
	printf '#define M3DS_BUILD_COMMIT "%s"\n#define M3DS_BUILD_DIRTY %s\n' "$$commit" "$$dirty" > $@.tmp; \
	if cmp -s $@.tmp $@; then rm -f $@.tmp; else mv $@.tmp $@; fi

romfs-audio-clean:
	@rm -rf $(ROMFS)/audio
	@rm -f $(BUILD)/audio-index.h

audio-romfs:
	@test -d "$(UPSTREAM_MINDUSTRY)" || { echo "audio-romfs: UPSTREAM_MINDUSTRY is not a directory" >&2; exit 1; }
	@command -v ffmpeg >/dev/null 2>&1 || { echo "audio-romfs: ffmpeg is required" >&2; exit 1; }
	$(PYTHON) tools/build_audio_assets.py \
	  --upstream "$(UPSTREAM_MINDUSTRY)" \
	  --map audio/upstream-audio-map.json \
	  --triggers audio/upstream-audio-triggers.json \
	  --out $(AUDIO_ROMFS) \
	  --romfs-out $(AUDIO_ROMFS)
	@mkdir -p $(ROMFS)/audio $(BUILD)
	cp $(AUDIO_ROMFS)/audio/*.pcm $(AUDIO_ROMFS)/audio/audio-manifest.json $(ROMFS)/audio/
	cp $(AUDIO_ROMFS)/audio-index.h $(BUILD)/audio-index.h

$(ATLAS)/sprites.h $(ATLAS)/sprites.t3x &: tools/build_sprite_atlas.py $(wildcard tests/assets/*.png)
	$(PYTHON) tools/build_sprite_atlas.py --tex3ds "$(TEX3DS)" --output-dir $(ATLAS)

$(ROMFS)/sprites.t3x: $(ATLAS)/sprites.t3x
	@mkdir -p $(ROMFS)
	cp $< $@

clean:
	@echo clean ...
	@rm -fr $(BUILD) $(ATLAS) $(ROMFS) $(OUTPUT).3dsx $(OUTPUT).smdh $(OUTPUT).elf $(BUILD)/*.map

else

$(OUTPUT).3dsx : $(OUTPUT).elf $(_3DSXDEPS)

$(OFILES_SOURCES) : $(HFILES) $(TOPDIR)/$(BUILD)/build-config.h

ifneq ($(strip $(UPSTREAM_MINDUSTRY)),)
$(OFILES_SOURCES) : $(TOPDIR)/$(BUILD)/audio-index.h
endif
citro2d_backend.o : $(TOPDIR)/$(ATLAS)/sprites.h
main_3ds.o : $(TOPDIR)/$(BUILD_INFO)

$(OUTPUT).elf : $(OFILES)
ifneq ($(strip $(TEAVM_LOADER_DIR)),)
$(OUTPUT).elf : $(TEAVM_LOADER_DIR)/libm3dsloader.a
endif

-include $(DEPSDIR)/*.d

endif
