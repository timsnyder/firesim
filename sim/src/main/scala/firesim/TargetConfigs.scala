package firesim.firesim

import chisel3._
import chisel3.util.{log2Up}
import freechips.rocketchip.config.{Parameters, Config}
import freechips.rocketchip.diplomacy.{LazyModule, ValName, BufferParams}
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem.WithInclusiveCache
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.tilelink.BootROMParams
import freechips.rocketchip.devices.debug.DebugModuleParams
import boom.system.BoomTilesKey
import testchipip.{WithBlockDevice, BlockDeviceKey, BlockDeviceConfig, MemBenchKey, MemBenchParams}
import sifive.blocks.devices.uart.{PeripheryUARTKey, UARTParams}
import icenet._
import memblade.manager.{MemBladeKey, MemBladeParams, MemBladeQueueParams}
import memblade.client.{RemoteMemClientKey, RemoteMemClientConfig}
import memblade.cache.{DRAMCacheKey, DRAMCacheConfig, RemoteAccessDepths, WritebackDepths, MemoryQueueParams}
import memblade.prefetcher.{PrefetchRoCC, PrefetchConfig, StreamBufferConfig}

class WithBootROM extends Config((site, here, up) => {
  case BootROMParams => BootROMParams(
    contentFileName = s"./target-rtl/firechip/testchipip/bootrom/bootrom.rv${site(XLen)}.img")
})

class WithPeripheryBusFrequency(freq: BigInt) extends Config((site, here, up) => {
  case PeripheryBusKey => up(PeripheryBusKey).copy(frequency=freq)
})

class WithUARTKey extends Config((site, here, up) => {
   case PeripheryUARTKey => List(UARTParams(
     address = BigInt(0x54000000L),
     nTxEntries = 256,
     nRxEntries = 256))
})

class WithNICKey extends Config((site, here, up) => {
  case NICKey => NICConfig(
    inBufFlits = 8192,
    ctrlQueueDepth = 64,
    usePauser = true)
})

class WithMemBladeKey extends Config((site, here, up) => {
  case MemBladeKey => MemBladeParams(
    spanBytes = site(CacheBlockBytes),
    nSpanTrackers = 4,
    nWordTrackers = 4,
    spanQueue = MemBladeQueueParams(reqHeadDepth = 32, respHeadDepth = 32),
    wordQueue = MemBladeQueueParams(reqHeadDepth = 32, respHeadDepth = 32))
})

class WithRemoteMemClientKey extends Config((site, here, up) => {
  case RemoteMemClientKey => RemoteMemClientConfig(
    spanBytes = site(CacheBlockBytes),
    nRMemXacts = 64,
    reqTimeout = Some(1000000))
})

class WithMemBenchKey extends Config((site, here, up) => {
  case MemBenchKey => MemBenchParams(nXacts = 32)
})

class WithDRAMCacheKey extends Config((site, here, up) => {
  case DRAMCacheKey => DRAMCacheConfig(
    nSets = 1 << 21,
    nWays = 7,
    baseAddr = BigInt(1) << 37,
    nTrackersPerBank = 4,
    nBanksPerChannel = 2,
    nChannels = 4,
    nSecondaryRequests = 1,
    spanBytes = site(CacheBlockBytes),
    chunkBytes = site(CacheBlockBytes),
    logAddrBits = 37,
    outIdBits = 4,
    prefetch = Some(StreamBufferConfig(
      nBuffers = 4,
      nBlocks = 16,
      hitThreshold = 1,
      reqQueue = 4,
      timeoutPeriod = 4096)),
    remAccessQueue = RemoteAccessDepths(1, 2, 1, 2),
    wbQueue = WritebackDepths(1, 1),
    memInQueue = MemoryQueueParams(8, 2, 8, 2, 8, 2),
    memOutQueue = MemoryQueueParams(2, 2, 2, 2, 2, 2),
    zeroMetadata = false)
})

//class WithPFA extends Config((site, here, up) => {
//  case HasPFA => true
//})

class WithPrefetchRoCC extends Config((site, here, up) => {
  case BuildRoCC => Seq((q: Parameters) => {
    implicit val p = q
    implicit val valName = ValName("FireSim")
    LazyModule(new PrefetchRoCC(
      OpcodeSet.custom2,
      PrefetchConfig(nMemXacts = 32, nBackends = 2)))
  })
})

class WithRocketL2TLBs(entries: Int) extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey) map (tile => tile.copy(
    core = tile.core.copy(
      nL2TLBEntries = entries
    )
  ))
})

class WithPerfCounters extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey) map (tile => tile.copy(
    core = tile.core.copy(nPerfCounters = 29)
  ))
})

class WithBoomL2TLBs(entries: Int) extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey) map (tile => tile.copy(
    core = tile.core.copy(nL2TLBEntries = entries)
  ))
})

// Disables clock-gating; doesn't play nice with our FAME-1 pass
class WithoutClockGating extends Config((site, here, up) => {
  case DebugModuleParams => up(DebugModuleParams, site).copy(clockGate = false)
})

// This is strictly speakig a MIDAS config, but it's target dependent -> mix in to target config
class WithBoomSynthAssertExcludes extends Config((site, here, up) => {
  case midas.ExcludeInstanceAsserts => Seq(
    // Boom instantiates duplicates of these module(s) with the expectation
    // the backend tool will optimize them away. FIXME.
    ("NonBlockingDCache", "dtlb"))
})

// Testing configurations
// This enables printfs used in testing
class WithScalaTestFeatures extends Config((site, here, up) => {
    case PrintTracePort => true
})

/*******************************************************************************
* Full TARGET_CONFIG configurations. These set parameters of the target being
* simulated.
*
* In general, if you're adding or removing features from any of these, you
* should CREATE A NEW ONE, WITH A NEW NAME. This is because the manager
* will store this name as part of the tags for the AGFI, so that later you can
* reconstruct what is in a particular AGFI. These tags are also used to
* determine which driver to build.
*******************************************************************************/
class FireSimRocketChipConfig extends Config(
  new WithBootROM ++
  new WithPeripheryBusFrequency(BigInt(3200000000L)) ++
  new WithExtMemSize(0x400000000L) ++ // 16GB
  new WithoutTLMonitors ++
  new WithUARTKey ++
  new WithNICKey ++
  new WithBlockDevice ++
  new WithRocketL2TLBs(1024) ++
  new WithPerfCounters ++
  new WithoutClockGating ++
  new freechips.rocketchip.system.DefaultConfig)

class WithNDuplicatedRocketCores(n: Int) extends Config((site, here, up) => {
  case RocketTilesKey => List.tabulate(n)(i => up(RocketTilesKey).head.copy(hartId = i))
})

// single core config
class FireSimRocketChipSingleCoreConfig extends Config(new FireSimRocketChipConfig)

// single core config with L2
class FireSimRocketChipSingleCoreL2Config extends Config(
  new WithInclusiveCache ++
  new FireSimRocketChipSingleCoreConfig)

// dual core config
class FireSimRocketChipDualCoreConfig extends Config(
  new WithNDuplicatedRocketCores(2) ++
  new FireSimRocketChipSingleCoreConfig)

// quad core config
class FireSimRocketChipQuadCoreConfig extends Config(
  new WithNDuplicatedRocketCores(4) ++
  new FireSimRocketChipSingleCoreConfig)

// hexa core config
class FireSimRocketChipHexaCoreConfig extends Config(
  new WithNDuplicatedRocketCores(6) ++
  new FireSimRocketChipSingleCoreConfig)

// octa core config
class FireSimRocketChipOctaCoreConfig extends Config(
  new WithNDuplicatedRocketCores(8) ++
  new FireSimRocketChipSingleCoreConfig)

class FireSimMemBladeConfig extends Config(
  new WithMemBladeKey ++ new FireSimRocketChipConfig)

class FireSimRemoteMemClientConfig extends Config(
  new WithRemoteMemClientKey ++ new FireSimRocketChipConfig)

class FireSimRemoteMemClientSingleCoreConfig extends Config(
  new WithNBigCores(1) ++ new FireSimRemoteMemClientConfig)

class FireSimRemoteMemClientDualCoreConfig extends Config(
  new WithNBigCores(2) ++ new FireSimRemoteMemClientConfig)

class FireSimRemoteMemClientQuadCoreConfig extends Config(
  new WithNBigCores(4) ++ new FireSimRemoteMemClientConfig)

class FireSimPrefetcherConfig extends Config(
  new WithPrefetchRoCC ++
  new FireSimRocketChipConfig)

class FireSimPrefetcherSingleCoreConfig extends Config(
  new WithNBigCores(1) ++ new FireSimPrefetcherConfig)

class FireSimPrefetcherDualCoreConfig extends Config(
  new WithNBigCores(2) ++ new FireSimPrefetcherConfig)

class FireSimPrefetcherQuadCoreConfig extends Config(
  new WithNBigCores(4) ++ new FireSimPrefetcherConfig)

class FireSimDRAMCacheConfig extends Config(
  new WithPrefetchRoCC ++
  new WithMemBenchKey ++
  new WithDRAMCacheKey ++
  new WithExtMemSize(15L << 30) ++
  new WithInclusiveCache(
    nBanks = 4,
    capacityKB = 1024,
    outerLatencyCycles = 50) ++
  new FireSimRocketChipConfig)

class FireSimDRAMCacheSingleCoreConfig extends Config(
  new WithNBigCores(1) ++ new FireSimDRAMCacheConfig)

class FireSimDRAMCacheDualCoreConfig extends Config(
  new WithNBigCores(2) ++ new FireSimDRAMCacheConfig)

class FireSimDRAMCacheQuadCoreConfig extends Config(
  new WithNBigCores(4) ++ new FireSimDRAMCacheConfig)

class FireSimBoomConfig extends Config(
  new WithBootROM ++
  new WithPeripheryBusFrequency(BigInt(3200000000L)) ++
  new WithExtMemSize(0x400000000L) ++ // 16GB
  new WithoutTLMonitors ++
  new WithUARTKey ++
  new WithNICKey ++
  new WithBlockDevice ++
  new WithBoomL2TLBs(1024) ++
  new WithoutClockGating ++
  new WithBoomSynthAssertExcludes ++ // Will do nothing unless assertion synth is enabled
  // Using a small config because it has 64-bit system bus, and compiles quickly
  new boom.system.SmallBoomConfig)

// A safer implementation than the one in BOOM in that it
// duplicates whatever BOOMTileKey.head is present N times. This prevents
// accidentally (and silently) blowing away configurations that may change the
// tile in the "up" view
class WithNDuplicatedBoomCores(n: Int) extends Config((site, here, up) => {
  case BoomTilesKey => List.tabulate(n)(i => up(BoomTilesKey).head.copy(hartId = i))
  case MaxHartIdBits => log2Up(site(BoomTilesKey).size)
})

class FireSimBoomDualCoreConfig extends Config(
  new WithNDuplicatedBoomCores(2) ++
  new FireSimBoomConfig)

class FireSimBoomQuadCoreConfig extends Config(
  new WithNDuplicatedBoomCores(4) ++
  new FireSimBoomConfig)

//**********************************************************************************
//* Supernode Configurations
//*********************************************************************************/
class WithNumNodes(n: Int) extends Config((pname, site, here) => {
  case NumNodes => n
})

class SupernodeFireSimRocketChipConfig extends Config(
  new WithNumNodes(4) ++
  new WithExtMemSize(0x200000000L) ++ // 8GB
  new FireSimRocketChipConfig)

class SupernodeFireSimRocketChipSingleCoreConfig extends Config(
  new WithNumNodes(4) ++
  new WithExtMemSize(0x200000000L) ++ // 8GB
  new FireSimRocketChipSingleCoreConfig)

class SupernodeSixNodeFireSimRocketChipSingleCoreConfig extends Config(
  new WithNumNodes(6) ++
  new WithExtMemSize(0x40000000L) ++ // 1GB
  new FireSimRocketChipSingleCoreConfig)

class SupernodeEightNodeFireSimRocketChipSingleCoreConfig extends Config(
  new WithNumNodes(8) ++
  new WithExtMemSize(0x40000000L) ++ // 1GB
  new FireSimRocketChipSingleCoreConfig)

class SupernodeFireSimRocketChipDualCoreConfig extends Config(
  new WithNumNodes(4) ++
  new WithExtMemSize(0x200000000L) ++ // 8GB
  new FireSimRocketChipDualCoreConfig)

class SupernodeFireSimRocketChipQuadCoreConfig extends Config(
  new WithNumNodes(4) ++
  new WithExtMemSize(0x200000000L) ++ // 8GB
  new FireSimRocketChipQuadCoreConfig)

class SupernodeFireSimRocketChipHexaCoreConfig extends Config(
  new WithNumNodes(4) ++
  new WithExtMemSize(0x200000000L) ++ // 8GB
  new FireSimRocketChipHexaCoreConfig)

class SupernodeFireSimRocketChipOctaCoreConfig extends Config(
  new WithNumNodes(4) ++
  new WithExtMemSize(0x200000000L) ++ // 8GB
  new FireSimRocketChipOctaCoreConfig)
