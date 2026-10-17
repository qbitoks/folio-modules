# libfolioreflow.so: k2pdfopt's page reflow (KOReader's libk2pdfopt) + Leptonica, no OCR and no image codecs.
# Built by build.sh; K2PDFOPT_DIR and LEPTONICA_DIR point at the fetched sources.
cmake_minimum_required(VERSION 3.22)
project(folioreflow C)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SW_BUILD OFF CACHE BOOL "" FORCE)
set(BUILD_PROG OFF CACHE BOOL "" FORCE)
foreach(codec ZLIB PNG GIF JPEG TIFF WEBP OPENJPEG)
    set(ENABLE_${codec} OFF CACHE BOOL "" FORCE)
endforeach()
add_subdirectory(${LEPTONICA_DIR} leptonica EXCLUDE_FROM_ALL)

set(K2 ${K2PDFOPT_DIR})
add_library(folioreflow SHARED
    folio_reflow.c
    ${K2}/lib/koptreflow.c
    ${K2}/lib/setting.c
    ${K2}/k2pdfoptlib/bmpregion.c
    ${K2}/k2pdfoptlib/devprofile.c
    ${K2}/k2pdfoptlib/k2bmp.c
    ${K2}/k2pdfoptlib/k2file.c
    ${K2}/k2pdfoptlib/k2master.c
    ${K2}/k2pdfoptlib/k2mem.c
    ${K2}/k2pdfoptlib/k2ocr.c
    ${K2}/k2pdfoptlib/k2proc.c
    ${K2}/k2pdfoptlib/k2settings.c
    ${K2}/k2pdfoptlib/k2sys.c
    ${K2}/k2pdfoptlib/k2version.c
    ${K2}/k2pdfoptlib/pagelist.c
    ${K2}/k2pdfoptlib/pageregions.c
    ${K2}/k2pdfoptlib/textrows.c
    ${K2}/k2pdfoptlib/textwords.c
    ${K2}/k2pdfoptlib/wrapbmp.c
    ${K2}/willuslib/ansi.c
    ${K2}/willuslib/array.c
    ${K2}/willuslib/bmp.c
    ${K2}/willuslib/filelist.c
    ${K2}/willuslib/gslpolyfit.c
    ${K2}/willuslib/linux.c
    ${K2}/willuslib/math.c
    ${K2}/willuslib/mem.c
    ${K2}/willuslib/ocr.c
    ${K2}/willuslib/ocrwords.c
    ${K2}/willuslib/strbuf.c
    ${K2}/willuslib/string.c
    ${K2}/willuslib/token.c
    ${K2}/willuslib/wfile.c
    ${K2}/willuslib/willusversion.c
    ${K2}/willuslib/wsys.c
)
set_target_properties(folioreflow PROPERTIES C_VISIBILITY_PRESET hidden)
target_compile_definitions(folioreflow PRIVATE BUILDING_LIBK2PDFOPT NO_FILELIST)
target_compile_options(folioreflow PRIVATE -O2 -w)
target_include_directories(folioreflow PRIVATE ${K2}/lib ${K2}/k2pdfoptlib ${K2}/willuslib
    ${LEPTONICA_DIR}/src ${CMAKE_BINARY_DIR}/leptonica/src)
target_link_libraries(folioreflow PRIVATE leptonica m log)
target_link_options(folioreflow PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
