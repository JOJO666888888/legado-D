package io.legado.app.ui.book.material

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import io.legado.app.data.appDb
import io.legado.app.data.entities.Material

class MaterialListViewModel(application: Application) : AndroidViewModel(application) {

    val searchKey = MutableLiveData<String?>(null)

    fun flowByBook(bookName: String, bookAuthor: String) =
        appDb.materialDao.flowByBook(bookName, bookAuthor)

    fun flowActive() = appDb.materialDao.flowActive()

    fun flowSearch(key: String) = appDb.materialDao.flowSearch(key)

    fun flowTrash() = appDb.materialDao.flowTrash()

    fun getMaterial(id: Long): Material? = appDb.materialDao.get(id)

}
