package com.zjf.edgeai.ui.chat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zjf.edgeai.R
import com.zjf.edgeai.databinding.ItemChatMessageBinding
import com.zjf.edgeai.ui.ChatMessage
import com.zjf.edgeai.ui.ChatRole

class ChatMessageAdapter : ListAdapter<ChatMessage, ChatMessageAdapter.MessageHolder>(Diff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageHolder = MessageHolder(
        ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: MessageHolder, position: Int) = holder.bind(getItem(position))

    class MessageHolder(private val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            val context = binding.root.context
            binding.role.text = if (message.role == ChatRole.USER) {
                context.getString(R.string.role_user)
            } else {
                context.getString(R.string.role_assistant)
            }
            binding.message.text = if (message.streaming && message.text.isEmpty()) "▍" else message.text
            binding.container.gravity = if (message.role == ChatRole.USER) Gravity.END else Gravity.START
            binding.message.setBackgroundResource(
                if (message.role == ChatRole.USER) R.drawable.bg_message_user else R.drawable.bg_message_assistant
            )
        }
    }

    private object Diff : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem == newItem
    }
}
