package com.mockloc.domain.engine

/**
 * 路线循环模式枚举。
 */
enum class LoopMode {
    /**
     * 单向移动：到达最后一个航路点后停止并标记完成。
     */
    ONE_WAY,

    /**
     * 往返移动：到达最后一个航路点后反向移动回起点，到达起点后再次向终点移动，往复循环。
     */
    PING_PONG,

    /**
     * 闭环循环：到达最后一个航路点后衔接回到初始航路点并继续循环。
     */
    LOOP
}
