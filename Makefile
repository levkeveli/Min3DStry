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
SOURCES         := src/render src/sim src/net src/audio src/platform src/platform/3ds
DATA            :=
INCLUDES        := include
GRAPHICS        :=
ROMFS           := build/romfs
ATLAS           := build/atlas
AUDIO_ROMFS     := build/audio-romfs
BUILD_CONFIG    := $(BUILD)/build-config.h
UPSTREAM_MINDUSTRY ?=
PYTHON          ?= python3
TEX3DS          ?= tex3ds

ifneq ($(strip $(UPSTREAM_MINDUSTRY)),)
M3DS_AUDIO_ROMFS_VALUE := 1
else
M3DS_AUDIO_ROMFS_VALUE := 0
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

LIBS    := -lcitro2d -lcitro3d -lctru -lm

LIBDIRS := $(CTRULIB)

ifneq ($(CURDIR),$(TOPDIR)/$(BUILD))

export OUTPUT := $(CURDIR)/build/$(TARGET)
export TOPDIR := $(CURDIR)

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

ifneq ($(strip $(UPSTREAM_MINDUSTRY)),)
all: audio-romfs
else
all: romfs-audio-clean
endif

all: $(BUILD_CONFIG) $(ATLAS)/sprites.h $(ROMFS)/sprites.t3x
	@mkdir -p $(BUILD) $(dir $(OUTPUT))
	@$(MAKE) --no-print-directory -C $(BUILD) -f $(CURDIR)/Makefile

$(BUILD_CONFIG): force-config
	@mkdir -p $(BUILD)
	@printf '#define M3DS_AUDIO_ROMFS %s\n' '$(M3DS_AUDIO_ROMFS_VALUE)' > $@.tmp
	@if cmp -s $@.tmp $@; then rm -f $@.tmp; else mv $@.tmp $@; fi

force-config:

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

ifeq ($(M3DS_AUDIO_ROMFS_VALUE),1)
$(OFILES_SOURCES) : $(TOPDIR)/$(BUILD)/audio-index.h
endif
citro2d_backend.o : $(TOPDIR)/$(ATLAS)/sprites.h

$(OUTPUT).elf : $(OFILES)

-include $(DEPSDIR)/*.d

endif
