#include "platform/msav_inflate.h"

#include <string.h>

#define MSAV_MAX_BITS 15
#define MSAV_MAX_LIT_CODES 288
#define MSAV_MAX_DIST_CODES 32

typedef struct {
    const uint8_t *data;
    size_t size;
    size_t position;
    uint8_t bits;
    int bit_count;
    bool error;
} BitReader;

typedef struct {
    short count[MSAV_MAX_BITS + 1];
    short symbol[MSAV_MAX_LIT_CODES];
} Huffman;

static int read_bit(BitReader *reader) {
    if (reader->error) {
        return 0;
    }
    if (reader->bit_count == 0) {
        if (reader->position >= reader->size) {
            reader->error = true;
            return 0;
        }
        reader->bits = reader->data[reader->position++];
        reader->bit_count = 8;
    }
    int bit = reader->bits & 1u;
    reader->bits >>= 1u;
    --reader->bit_count;
    return bit;
}

static int read_bits(BitReader *reader, int count) {
    int value = 0;
    for (int i = 0; i < count; ++i) {
        value |= read_bit(reader) << i;
    }
    return value;
}

static int build_huffman(Huffman *huffman, const short *lengths, int count) {
    int offsets[MSAV_MAX_BITS + 1];
    for (int length = 0; length <= MSAV_MAX_BITS; ++length) {
        huffman->count[length] = 0;
    }
    for (int i = 0; i < count; ++i) {
        if (lengths[i] < 0 || lengths[i] > MSAV_MAX_BITS) {
            return -1;
        }
        ++huffman->count[lengths[i]];
    }
    if (huffman->count[0] == count) {
        return 0;
    }
    int left = 1;
    for (int length = 1; length <= MSAV_MAX_BITS; ++length) {
        left <<= 1;
        left -= huffman->count[length];
        if (left < 0) {
            return left;
        }
    }
    offsets[1] = 0;
    for (int length = 1; length < MSAV_MAX_BITS; ++length) {
        offsets[length + 1] = offsets[length] + huffman->count[length];
    }
    for (int i = 0; i < count; ++i) {
        if (lengths[i] != 0) {
            huffman->symbol[offsets[lengths[i]]++] = (short)i;
        }
    }
    return left;
}

static int decode_symbol(BitReader *reader, const Huffman *huffman) {
    int code = 0;
    int first = 0;
    int index = 0;
    for (int length = 1; length <= MSAV_MAX_BITS; ++length) {
        code |= read_bit(reader);
        if (reader->error) {
            return -1;
        }
        int count = huffman->count[length];
        if (code < first + count) {
            return huffman->symbol[index + (code - first)];
        }
        index += count;
        first += count;
        first <<= 1;
        code <<= 1;
    }
    return -1;
}

static bool decode_stored(BitReader *reader, uint8_t *out,
                          size_t out_capacity, size_t *out_size) {
    reader->bit_count = 0;
    int length = read_bits(reader, 16);
    int complement = read_bits(reader, 16);
    if (reader->error || (length ^ complement) != 0xffff) {
        return false;
    }
    for (int i = 0; i < length && *out_size < out_capacity; ++i) {
        out[(*out_size)++] = (uint8_t)read_bits(reader, 8);
    }
    return !reader->error;
}

static bool decode_huffman_block(
    BitReader *reader, uint8_t *out, size_t out_capacity, size_t *out_size,
    const Huffman *literal, const Huffman *distance) {
    static const short length_base[29] = {
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27,
        31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258};
    static const short length_extra[29] = {
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2,
        2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0};
    static const short distance_base[30] = {
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129,
        193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097,
        6145, 8193, 12289, 16385, 24577};
    static const short distance_extra[30] = {
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6,
        6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13};

    while (*out_size < out_capacity) {
        int symbol = decode_symbol(reader, literal);
        if (symbol < 0) {
            return false;
        }
        if (symbol < 256) {
            out[(*out_size)++] = (uint8_t)symbol;
            continue;
        }
        if (symbol == 256) {
            return true;
        }
        symbol -= 257;
        if (symbol >= 29) {
            return false;
        }
        int length = length_base[symbol] + read_bits(reader, length_extra[symbol]);
        int distance_symbol = decode_symbol(reader, distance);
        if (distance_symbol < 0 || distance_symbol >= 30) {
            return false;
        }
        int distance = distance_base[distance_symbol]
            + read_bits(reader, distance_extra[distance_symbol]);
        if (reader->error || distance <= 0 || (size_t)distance > *out_size) {
            return false;
        }
        for (int i = 0; i < length && *out_size < out_capacity; ++i) {
            out[*out_size] = out[*out_size - (size_t)distance];
            ++*out_size;
        }
    }
    return !reader->error;
}

static bool decode_dynamic_block(
    BitReader *reader, uint8_t *out, size_t out_capacity, size_t *out_size) {
    static const short order[19] = {
        16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};
    short lengths[MSAV_MAX_LIT_CODES + MSAV_MAX_DIST_CODES];
    int literal_count = read_bits(reader, 5) + 257;
    int distance_count = read_bits(reader, 5) + 1;
    int code_count = read_bits(reader, 4) + 4;
    if (reader->error || literal_count > MSAV_MAX_LIT_CODES
            || distance_count > MSAV_MAX_DIST_CODES) {
        return false;
    }
    memset(lengths, 0, sizeof(lengths));
    for (int i = 0; i < code_count; ++i) {
        lengths[order[i]] = (short)read_bits(reader, 3);
    }
    Huffman code_huffman;
    if (build_huffman(&code_huffman, lengths, 19) < 0) {
        return false;
    }
    memset(lengths, 0, sizeof(lengths));
    int total = literal_count + distance_count;
    int index = 0;
    while (index < total) {
        int symbol = decode_symbol(reader, &code_huffman);
        if (symbol < 0) {
            return false;
        }
        if (symbol < 16) {
            lengths[index++] = (short)symbol;
        } else if (symbol == 16) {
            if (index == 0) {
                return false;
            }
            short previous = lengths[index - 1];
            int repeat = 3 + read_bits(reader, 2);
            while (repeat-- > 0 && index < total) {
                lengths[index++] = previous;
            }
        } else if (symbol == 17 || symbol == 18) {
            int repeat = symbol == 17 ? 3 + read_bits(reader, 3)
                                      : 11 + read_bits(reader, 7);
            while (repeat-- > 0 && index < total) {
                lengths[index++] = 0;
            }
        } else {
            return false;
        }
    }
    if (reader->error) {
        return false;
    }
    Huffman literal_huffman;
    Huffman distance_huffman;
    if (build_huffman(&literal_huffman, lengths, literal_count) < 0
            || build_huffman(
                   &distance_huffman, lengths + literal_count, distance_count) < 0) {
        return false;
    }
    return decode_huffman_block(
        reader, out, out_capacity, out_size, &literal_huffman,
        &distance_huffman);
}

static bool decode_fixed_block(
    BitReader *reader, uint8_t *out, size_t out_capacity, size_t *out_size) {
    short lengths[MSAV_MAX_LIT_CODES];
    for (int i = 0; i < MSAV_MAX_LIT_CODES; ++i) {
        lengths[i] = (short)(i < 144 ? 8 : i < 256 ? 9 : i < 280 ? 7 : 8);
    }
    Huffman literal_huffman;
    if (build_huffman(&literal_huffman, lengths, MSAV_MAX_LIT_CODES) < 0) {
        return false;
    }
    short distance_lengths[MSAV_MAX_DIST_CODES];
    for (int i = 0; i < MSAV_MAX_DIST_CODES; ++i) {
        distance_lengths[i] = 5;
    }
    Huffman distance_huffman;
    if (build_huffman(
            &distance_huffman, distance_lengths, MSAV_MAX_DIST_CODES) < 0) {
        return false;
    }
    return decode_huffman_block(
        reader, out, out_capacity, out_size, &literal_huffman,
        &distance_huffman);
}

bool m3ds_msav_inflate_prefix(
    const uint8_t *zlib_data, size_t zlib_size, uint8_t *out,
    size_t out_capacity, size_t *out_size) {
    if (!zlib_data || zlib_size < 2 || !out || out_capacity == 0 || !out_size) {
        return false;
    }
    uint8_t cmf = zlib_data[0];
    uint8_t flg = zlib_data[1];
    if ((cmf & 0x0fu) != 8u || (cmf >> 4) > 7u || (flg & 0x20u) != 0u
            || ((((unsigned)cmf << 8) | flg) % 31u) != 0u) {
        return false;
    }
    BitReader reader = {zlib_data + 2, zlib_size - 2, 0, 0, 0, false};
    *out_size = 0;
    bool last = false;
    while (!last && *out_size < out_capacity) {
        last = read_bit(&reader) != 0;
        int type = read_bits(&reader, 2);
        if (reader.error || type < 0 || type > 2) {
            return false;
        }
        bool decoded;
        if (type == 0) {
            decoded = decode_stored(&reader, out, out_capacity, out_size);
        } else if (type == 1) {
            decoded = decode_fixed_block(&reader, out, out_capacity, out_size);
        } else {
            decoded = decode_dynamic_block(&reader, out, out_capacity, out_size);
        }
        if (!decoded) {
            return false;
        }
    }
    return *out_size > 0;
}
