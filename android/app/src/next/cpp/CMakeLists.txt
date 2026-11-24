# Built by CI (not by Gradle) once per ABI, into android/app/src/next/jniLibs/<abi>/libstrata_discord.so.
#   -DDISCORD_INCLUDE=<folder with discordpp.h + cdiscord.h>  -DDISCORD_SO=<that ABI's libdiscord_partner_sdk.so from the .aar>
cmake_minimum_required(VERSION 3.18)
project(strata_discord CXX)
set(CMAKE_CXX_STANDARD 17)
add_library(strata_discord SHARED strata_discord.cpp)
target_include_directories(strata_discord PRIVATE ${DISCORD_INCLUDE})
add_library(discord_sdk SHARED IMPORTED)
set_target_properties(discord_sdk PROPERTIES IMPORTED_LOCATION ${DISCORD_SO})
target_link_libraries(strata_discord discord_sdk log)
target_link_options(strata_discord PRIVATE -Wl,-z,max-page-size=16384)
