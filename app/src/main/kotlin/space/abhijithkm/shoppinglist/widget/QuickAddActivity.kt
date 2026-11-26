package space.abhijithkm.shoppinglist.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.data.repository.LocalShoppingRepository
import space.abhijithkm.shoppinglist.ui.theme.ShoppingListTheme

/**
 * A small dialog over the home screen: a widget cannot host a text field, so its "+" opens this. The text goes through
 * the same quick-add parser as the app ("2 kg milk"), then the dialog closes.
 */
@AndroidEntryPoint
class QuickAddActivity : ComponentActivity() {
    @Inject lateinit var repository: LocalShoppingRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val listId = intent.getStringExtra(EXTRA_LIST_ID)
        if (listId == null) {
            finish()
            return
        }
        setContent {
            ShoppingListTheme {
                var text by remember { mutableStateOf("") }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                val add = {
                    lifecycleScope.launch {
                        if (text.isNotBlank()) repository.addItem(listId, text)
                        finish()
                    }
                    Unit
                }
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(stringResource(R.string.quick_add_title)) },
                    text = {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.quick_add_hint)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { add() }),
                            modifier = Modifier.focusRequester(focus),
                        )
                    },
                    confirmButton = { TextButton(onClick = { add() }) { Text(stringResource(R.string.quick_add_confirm)) } },
                    dismissButton = { TextButton(onClick = { finish() }) { Text(stringResource(R.string.quick_add_cancel)) } },
                )
            }
        }
    }

    companion object {
        const val EXTRA_LIST_ID = "list_id"
    }
}
