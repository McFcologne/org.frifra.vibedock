# whd_gen: converts an IWAD to the compact WHX that the Doom firmware reads from flash (usage: whd_gen in.wad out.whx).
CXX ?= g++
CC ?= gcc
OPT = -O2 -w -DIS_WHD_GEN=1 -Isrc -Isrc/doom -Isrc/whd_gen -Isrc/adpcm-xq -include stdint.h
whd_gen: src/whd_gen/whd_gen.cpp src/whd_gen/mus2seq.cpp src/whd_gen/huff.cpp src/whd_gen/lodepng.cpp src/whd_gen/compress_mus.cpp src/whd_gen/wad.cpp src/tiny_huff.c src/musx_decoder.c src/image_decoder.c src/adpcm-xq/adpcm-lib.c
	mkdir -p obj && for f in whd_gen mus2seq huff lodepng compress_mus wad; do $(CXX) -std=c++17 $(OPT) -c src/whd_gen/$$f.cpp -o obj/$$f.o || exit 1; done \
	  && for f in src/tiny_huff src/musx_decoder src/image_decoder src/adpcm-xq/adpcm-lib; do $(CC) $(OPT) -c $$f.c -o obj/$$(basename $$f).o || exit 1; done \
	  && $(CXX) -static-libstdc++ -static-libgcc obj/*.o -o whd_gen
clean:
	rm -rf obj whd_gen
