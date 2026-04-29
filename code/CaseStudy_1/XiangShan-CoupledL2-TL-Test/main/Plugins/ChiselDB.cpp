#include "ChiselDB.hpp"

#include "../Utils/autoinclude.h"
#include "../Utils/Common.h"

#include AUTOINCLUDE_CHISELDB(chisel_db.cpp)
#include AUTOINCLUDE_CHISELDB(perfCCT.cpp)


#define TLTEST_CHISELDB_ALIAS_ROLLING_1(name) \
    extern "C" __attribute__((weak)) void name##_rolling_1_write( \
        long long yAxisPt, long long xAxisPt, long long stamp, const char* site) \
    { \
        name##_rolling_0_write(yAxisPt, xAxisPt, stamp, const_cast<char*>(site)); \
    }

TLTEST_CHISELDB_ALIAS_ROLLING_1(L2PrefetchAccuracyBOP)
TLTEST_CHISELDB_ALIAS_ROLLING_1(L2PrefetchAccuracyPBOP)
TLTEST_CHISELDB_ALIAS_ROLLING_1(L2PrefetchAccuracySMS)
TLTEST_CHISELDB_ALIAS_ROLLING_1(L2PrefetchAccuracyStream)
TLTEST_CHISELDB_ALIAS_ROLLING_1(L2PrefetchAccuracyStride)
TLTEST_CHISELDB_ALIAS_ROLLING_1(L2PrefetchAccuracyTP)
TLTEST_CHISELDB_ALIAS_ROLLING_1(L2PrefetchAccuracy)
TLTEST_CHISELDB_ALIAS_ROLLING_1(L2PrefetchCoverageBOP)
TLTEST_CHISELDB_ALIAS_ROLLING_1(L2PrefetchCoveragePBOP)
TLTEST_CHISELDB_ALIAS_ROLLING_1(L2PrefetchCoverageSMS)
TLTEST_CHISELDB_ALIAS_ROLLING_1(L2PrefetchCoverageStream)
TLTEST_CHISELDB_ALIAS_ROLLING_1(L2PrefetchCoverageStride)
TLTEST_CHISELDB_ALIAS_ROLLING_1(L2PrefetchCoverageTP)
TLTEST_CHISELDB_ALIAS_ROLLING_1(L2PrefetchCoverage)
TLTEST_CHISELDB_ALIAS_ROLLING_1(L2PrefetchLate)

#undef TLTEST_CHISELDB_ALIAS_ROLLING_1

extern "C" __attribute__((weak)) void TLLog_write(
    long long echo,
    long long user,
    long long data_0,
    long long data_1,
    long long data_2,
    long long data_3,
    long long address,
    long long sink,
    long long source,
    long long param,
    long long opcode,
    long long channel,
    long long stamp,
    const char* site)
{
    (void) echo;
    (void) user;
    (void) data_0;
    (void) data_1;
    (void) data_2;
    (void) data_3;
    (void) address;
    (void) sink;
    (void) source;
    (void) param;
    (void) opcode;
    (void) channel;
    (void) stamp;
    (void) site;
}

extern "C" __attribute__((weak)) void L1TLLog_write(
    long long echo,
    long long user,
    long long data_0,
    long long data_1,
    long long data_2,
    long long data_3,
    long long address,
    long long sink,
    long long source,
    long long param,
    long long opcode,
    long long channel,
    long long stamp,
    const char* site)
{
    (void) echo;
    (void) user;
    (void) data_0;
    (void) data_1;
    (void) data_2;
    (void) data_3;
    (void) address;
    (void) sink;
    (void) source;
    (void) param;
    (void) opcode;
    (void) channel;
    (void) stamp;
    (void) site;
}


//
void ChiselDB::InitDB()
{
    init_db(true, false, "");
}

//
void ChiselDB::SaveDB(const char* file)
{
    save_db(file);
}


// Implementation of: class PluginInstance
namespace ChiselDB {

    PluginInstance::PluginInstance() noexcept
        : Plugin    ("chiseldb")
    { }

    std::string PluginInstance::GetDisplayName() const noexcept
    {
        return "ChiselDB";
    }

    std::string PluginInstance::GetDescription() const noexcept
    {
        return "ChiselDB Compatibility Plugin for TL-Test New";
    }

    std::string PluginInstance::GetVersion() const noexcept
    {
        return "Kunming Lake";
    }

    void PluginInstance::OnEnable()
    {
        LogInfo("ChiselDB", Append("Enabled").EndLine());

        InitDB();

        LogInfo("ChiselDB", Append("DB Initialized").EndLine());
    }

    void PluginInstance::OnDisable()
    {
        const char* file = "chiseldb.db";

        LogInfo("ChiselDB", Append("Saving DB to: ", file).EndLine());

        SaveDB(file);

        LogInfo("ChiselDB", Append("Saved DB").EndLine());
        LogInfo("ChiselDB", Append("Disabled").EndLine());
    }
}
