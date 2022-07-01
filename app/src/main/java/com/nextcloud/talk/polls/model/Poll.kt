/*
 * Nextcloud Talk application
 *
 * @author Marcel Hibbe
 * Copyright (C) 2022 Marcel Hibbe <dev@mhibbe.de>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nextcloud.talk.polls.model

data class Poll(
    val id: String,
    val question: String?,
    val options: List<String>?,
    val votes: Map<String, Int>?,
    val actorType: String?,
    val actorId: String?,
    val actorDisplayName: String?,
    val status: Int,
    val resultMode: Int,
    val maxVotes: Int,
    val votedSelf: List<Int>?,
    val numVoters: Int,
    val details: List<PollDetails>?,
    val totalVotes: Int
) {
    companion object {
        const val STATUS_OPEN: Int = 0
        const val STATUS_CLOSED: Int = 1
        const val RESULT_MODE_PUBLIC: Int = 0
        const val RESULT_MODE_HIDDEN: Int = 1
    }
}
