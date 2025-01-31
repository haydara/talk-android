/*
 * Nextcloud Talk application
 *
 * @author Mario Danic
 * Copyright (C) 2017-2019 Mario Danic <mario@lovelyhq.com>
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

package com.nextcloud.talk.utils

import android.os.Bundle
import android.util.Log
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.changehandler.HorizontalChangeHandler
import com.bluelinelabs.conductor.changehandler.SimpleSwapChangeHandler
import com.nextcloud.talk.controllers.ChatController
import com.nextcloud.talk.controllers.ConversationsListController
import com.nextcloud.talk.controllers.LockedController

object ConductorRemapping {

    private val TAG = ConductorRemapping::class.simpleName

    fun remapChatController(
        router: Router,
        internalUserId: Long,
        roomTokenOrId: String,
        bundle: Bundle,
        replaceTop: Boolean
    ) {
        remapChatController(router, internalUserId, roomTokenOrId, bundle, replaceTop, false)
    }

    fun remapChatController(
        router: Router,
        internalUserId: Long,
        roomTokenOrId: String,
        bundle: Bundle,
        replaceTop: Boolean,
        pushImmediately: Boolean
    ) {
        val chatControllerTag = "$internalUserId@$roomTokenOrId"

        if (router.getControllerWithTag(chatControllerTag) != null) {
            moveControllerToTop(router, chatControllerTag)
        } else {
            val pushChangeHandler = if (pushImmediately) {
                SimpleSwapChangeHandler()
            } else {
                HorizontalChangeHandler()
            }
            if (!replaceTop) {
                if (!router.hasRootController()) {
                    Log.d(TAG, "router has no RootController. creating backstack with ConversationsListController")
                    val newBackstack = listOf(
                        RouterTransaction.with(ConversationsListController(Bundle()))
                            .pushChangeHandler(HorizontalChangeHandler())
                            .popChangeHandler(HorizontalChangeHandler()),
                        RouterTransaction.with(ChatController(bundle))
                            .pushChangeHandler(HorizontalChangeHandler())
                            .popChangeHandler(HorizontalChangeHandler()).tag(chatControllerTag)
                    )
                    router.setBackstack(newBackstack, SimpleSwapChangeHandler())
                } else {
                    Log.d(TAG, "router has RootController. pushing ChatController")
                    router.pushController(
                        RouterTransaction.with(ChatController(bundle))
                            .pushChangeHandler(pushChangeHandler)
                            .popChangeHandler(HorizontalChangeHandler()).tag(chatControllerTag)
                    )
                }
            } else {
                Log.d(TAG, "ChatController replace topController")

                router.replaceTopController(
                    RouterTransaction.with(ChatController(bundle))
                        .pushChangeHandler(pushChangeHandler)
                        .popChangeHandler(HorizontalChangeHandler()).tag(chatControllerTag)
                )
            }
        }

        if (router.getControllerWithTag(LockedController.TAG) != null) {
            moveControllerToTop(router, LockedController.TAG)
        }
    }

    private fun moveControllerToTop(router: Router, controllerTag: String) {
        Log.d(TAG, "moving $controllerTag to top...")
        val backstack = router.backstack
        var routerTransaction: RouterTransaction? = null
        for (i in 0 until router.backstackSize) {
            if (controllerTag == backstack[i].tag()) {
                routerTransaction = backstack[i]
                backstack.remove(routerTransaction)
                Log.d(TAG, "removed controller: " + routerTransaction.controller)
                break
            }
        }

        backstack.add(routerTransaction)
        Log.d(TAG, "added controller to top: " + routerTransaction!!.controller)
        router.setBackstack(backstack, HorizontalChangeHandler())
    }
}
