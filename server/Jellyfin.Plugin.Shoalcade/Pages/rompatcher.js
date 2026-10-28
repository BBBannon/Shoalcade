/*
 * RomPatcher.js 3.2.1 core, joined unchanged for Shoalcade's patcher page by tools/vendor_rompatcher.py.
 * Source: https://github.com/marcrobledo/RomPatcher.js/archive/refs/tags/v3.2.1.tar.gz
 * SHA-256 of that archive: 95d4ac04713158fca7706468bb93893b6d1ca3b136f595c8324eb1877128275f
 *
 * MIT License
 * 
 * Copyright (c) 2017-2024 Marc Robledo
 * 
 * This project incorporates components from Octicons
 * (https://github.com/primer/octicons/) Copyright (c) 2024 GitHub Inc.,
 * also released under MIT license.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

/* ---- modules/HashCalculator.js ---- */
/*
* HashCalculator.js (last update: 2021-08-15)
* by Marc Robledo, https://www.marcrobledo.com
* 
* data hash calculator (CRC32, MD5, SHA1, ADLER-32, CRC16)
* 
* MIT License
* 
* Copyright (c) 2016-2021 Marc Robledo
* 
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
* 
* The above copyright notice and this permission notice shall be included in all
* copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
* SOFTWARE.
*/


const HashCalculator = (function () {
	const HEX_CHR = '0123456789abcdef'.split('');

	/* MD5 helpers */
	const _add32 = function (a, b) { return (a + b) & 0xffffffff }
	const _md5cycle = function (x, k) { var a = x[0], b = x[1], c = x[2], d = x[3]; a = ff(a, b, c, d, k[0], 7, -680876936); d = ff(d, a, b, c, k[1], 12, -389564586); c = ff(c, d, a, b, k[2], 17, 606105819); b = ff(b, c, d, a, k[3], 22, -1044525330); a = ff(a, b, c, d, k[4], 7, -176418897); d = ff(d, a, b, c, k[5], 12, 1200080426); c = ff(c, d, a, b, k[6], 17, -1473231341); b = ff(b, c, d, a, k[7], 22, -45705983); a = ff(a, b, c, d, k[8], 7, 1770035416); d = ff(d, a, b, c, k[9], 12, -1958414417); c = ff(c, d, a, b, k[10], 17, -42063); b = ff(b, c, d, a, k[11], 22, -1990404162); a = ff(a, b, c, d, k[12], 7, 1804603682); d = ff(d, a, b, c, k[13], 12, -40341101); c = ff(c, d, a, b, k[14], 17, -1502002290); b = ff(b, c, d, a, k[15], 22, 1236535329); a = gg(a, b, c, d, k[1], 5, -165796510); d = gg(d, a, b, c, k[6], 9, -1069501632); c = gg(c, d, a, b, k[11], 14, 643717713); b = gg(b, c, d, a, k[0], 20, -373897302); a = gg(a, b, c, d, k[5], 5, -701558691); d = gg(d, a, b, c, k[10], 9, 38016083); c = gg(c, d, a, b, k[15], 14, -660478335); b = gg(b, c, d, a, k[4], 20, -405537848); a = gg(a, b, c, d, k[9], 5, 568446438); d = gg(d, a, b, c, k[14], 9, -1019803690); c = gg(c, d, a, b, k[3], 14, -187363961); b = gg(b, c, d, a, k[8], 20, 1163531501); a = gg(a, b, c, d, k[13], 5, -1444681467); d = gg(d, a, b, c, k[2], 9, -51403784); c = gg(c, d, a, b, k[7], 14, 1735328473); b = gg(b, c, d, a, k[12], 20, -1926607734); a = hh(a, b, c, d, k[5], 4, -378558); d = hh(d, a, b, c, k[8], 11, -2022574463); c = hh(c, d, a, b, k[11], 16, 1839030562); b = hh(b, c, d, a, k[14], 23, -35309556); a = hh(a, b, c, d, k[1], 4, -1530992060); d = hh(d, a, b, c, k[4], 11, 1272893353); c = hh(c, d, a, b, k[7], 16, -155497632); b = hh(b, c, d, a, k[10], 23, -1094730640); a = hh(a, b, c, d, k[13], 4, 681279174); d = hh(d, a, b, c, k[0], 11, -358537222); c = hh(c, d, a, b, k[3], 16, -722521979); b = hh(b, c, d, a, k[6], 23, 76029189); a = hh(a, b, c, d, k[9], 4, -640364487); d = hh(d, a, b, c, k[12], 11, -421815835); c = hh(c, d, a, b, k[15], 16, 530742520); b = hh(b, c, d, a, k[2], 23, -995338651); a = ii(a, b, c, d, k[0], 6, -198630844); d = ii(d, a, b, c, k[7], 10, 1126891415); c = ii(c, d, a, b, k[14], 15, -1416354905); b = ii(b, c, d, a, k[5], 21, -57434055); a = ii(a, b, c, d, k[12], 6, 1700485571); d = ii(d, a, b, c, k[3], 10, -1894986606); c = ii(c, d, a, b, k[10], 15, -1051523); b = ii(b, c, d, a, k[1], 21, -2054922799); a = ii(a, b, c, d, k[8], 6, 1873313359); d = ii(d, a, b, c, k[15], 10, -30611744); c = ii(c, d, a, b, k[6], 15, -1560198380); b = ii(b, c, d, a, k[13], 21, 1309151649); a = ii(a, b, c, d, k[4], 6, -145523070); d = ii(d, a, b, c, k[11], 10, -1120210379); c = ii(c, d, a, b, k[2], 15, 718787259); b = ii(b, c, d, a, k[9], 21, -343485551); x[0] = _add32(a, x[0]); x[1] = _add32(b, x[1]); x[2] = _add32(c, x[2]); x[3] = _add32(d, x[3]) }
	const _md5blk = function (d) { var md5blks = [], i; for (i = 0; i < 64; i += 4)md5blks[i >> 2] = d[i] + (d[i + 1] << 8) + (d[i + 2] << 16) + (d[i + 3] << 24); return md5blks }
	const _cmn = function (q, a, b, x, s, t) { a = _add32(_add32(a, q), _add32(x, t)); return _add32((a << s) | (a >>> (32 - s)), b) }
	const ff = function (a, b, c, d, x, s, t) { return _cmn((b & c) | ((~b) & d), a, b, x, s, t) }
	const gg = function (a, b, c, d, x, s, t) { return _cmn((b & d) | (c & (~d)), a, b, x, s, t) }
	const hh = function (a, b, c, d, x, s, t) { return _cmn(b ^ c ^ d, a, b, x, s, t) }
	const ii = function (a, b, c, d, x, s, t) { return _cmn(c ^ (b | (~d)), a, b, x, s, t) }

	/* CRC32 helpers */
	const CRC32_TABLE = (function () {
		var c, crcTable = [];
		for (var n = 0; n < 256; n++) {
			c = n;
			for (var k = 0; k < 8; k++)
				c = ((c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1));
			crcTable[n] = c;
		}
		return crcTable;
	}());

	/* Adler-32 helpers */
	const ADLER32_MOD = 0xfff1;




	const generateUint8Array = function (arrayBuffer, offset, len) {
		if (typeof offset !== 'number' || offset < 0)
			offset = 0;
		else if (offset < arrayBuffer.byteLength)
			offset = Math.floor(offset);
		else
			throw new Error('out of bounds slicing');

		if (typeof len !== 'number' || len < 0 || (offset + len) >= arrayBuffer.byteLength.length)
			len = arrayBuffer.byteLength - offset;
		else if (len > 0)
			len = Math.floor(len);
		else
			throw new Error('zero length provided for slicing');

		return new Uint8Array(arrayBuffer, offset, len);
	}


	return {
		/* SHA-1 using WebCryptoAPI */
		sha1: async function sha1(arrayBuffer, offset, len) {
			if(typeof window === 'undefined' || typeof window.crypto === 'undefined')
				throw new Error('Web Crypto API is not available');
				
			const u8array = generateUint8Array(arrayBuffer, offset, len);
			if (u8array.byteLength !== arrayBuffer.byteLength) {
				arrayBuffer = arrayBuffer.slice(u8array.byteOffset, u8array.byteOffset + u8array.byteLength);
			}

			const hash = await window.crypto.subtle.digest('SHA-1', arrayBuffer);

			const bytes = new Uint8Array(hash);
			let hexString = '';
			for (let i = 0; i < bytes.length; i++)
				hexString += bytes[i] < 16 ? '0' + bytes[i].toString(16) : bytes[i].toString(16);
			return hexString;
		},

		/* MD5 - from Joseph's Myers - http://www.myersdaily.org/joseph/javascript/md5.js */
		md5: function (arrayBuffer, offset, len) {
			let u8array = generateUint8Array(arrayBuffer, offset, len);

			var n = u8array.byteLength, state = [1732584193, -271733879, -1732584194, 271733878], i;
			for (i = 64; i <= u8array.byteLength; i += 64)
				_md5cycle(state, _md5blk(u8array.slice(i - 64, i)));
			u8array = u8array.slice(i - 64);
			var tail = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
			for (i = 0; i < u8array.byteLength; i++)
				tail[i >> 2] |= u8array[i] << ((i % 4) << 3);
			tail[i >> 2] |= 0x80 << ((i % 4) << 3);
			if (i > 55) {
				_md5cycle(state, tail);
				for (i = 0; i < 16; i++)tail[i] = 0;
			}
			tail[14] = n * 8;
			tail[15] = Math.floor(n / 536870912) >>> 0; //if file is bigger than 512Mb*8, value is bigger than 32 bits, so it needs two words to store its length
			_md5cycle(state, tail);

			for (var i = 0; i < state.length; i++) {
				var s = '', j = 0;
				for (; j < 4; j++)
					s += HEX_CHR[(state[i] >> (j * 8 + 4)) & 0x0f] + HEX_CHR[(state[i] >> (j * 8)) & 0x0f];
				state[i] = s;
			}
			return state.join('')
		},

		/* CRC32 - from Alex - https://stackoverflow.com/a/18639999 */
		crc32: function (arrayBuffer, offset, len) {
			const u8array = generateUint8Array(arrayBuffer, offset, len);

			var crc = 0 ^ (-1);

			for (var i = 0; i < u8array.byteLength; i++)
				crc = (crc >>> 8) ^ CRC32_TABLE[(crc ^ u8array[i]) & 0xff];

			return ((crc ^ (-1)) >>> 0);
		},

		/* Adler-32 - https://en.wikipedia.org/wiki/Adler-32#Example_implementation */
		adler32: function (arrayBuffer, offset, len) {
			const u8array = generateUint8Array(arrayBuffer, offset, len);

			var a = 1, b = 0;

			for (var i = 0; i < u8array.byteLength; i++) {
				a = (a + u8array[i]) % ADLER32_MOD;
				b = (b + a) % ADLER32_MOD;
			}

			return ((b << 16) | a) >>> 0;
		},

		/* CRC16/CCITT-FALSE */
		crc16: function (arrayBuffer, offset, len) {
			const u8array = generateUint8Array(arrayBuffer, offset, len);

			var crc = 0xffff;

			var offset = 0;

			for (var i = 0; i < u8array.byteLength; i++) {
				crc ^= u8array[offset++] << 8;
				for (j = 0; j < 8; ++j) {
					crc = (crc & 0x8000) >>> 0 ? (crc << 1) ^ 0x1021 : crc << 1;
				}
			}

			return crc & 0xffff;
		}
	}
}());


if (typeof module !== 'undefined' && module.exports) {
	module.exports = HashCalculator;
}

/* ---- modules/BinFile.js ---- */
/*
* BinFile.js (last update: 2024-08-21)
* by Marc Robledo, https://www.marcrobledo.com
* 
* a JS class for reading/writing sequentially binary data from/to a file
* that allows much more manipulation than simple DataView
* compatible with both browsers and Node.js
* 
* MIT License
* 
* Copyright (c) 2014-2024 Marc Robledo
* 
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
* 
* The above copyright notice and this permission notice shall be included in all
* copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
* SOFTWARE.
*/



function BinFile(source, onLoad) {
	this.littleEndian = false;
	this.offset = 0;
	this._lastRead = null;
	this._offsetsStack = [];


	if (
		BinFile.RUNTIME_ENVIROMENT === 'browser' && (
			source instanceof File ||
			source instanceof FileList ||
			(source instanceof HTMLElement && source.tagName === 'INPUT' && source.type === 'file')
		)
	) {
		if (source instanceof HTMLElement)
			source = source.files;
		if (source instanceof FileList)
			source = source[0];

		this.fileName = source.name;
		this.fileType = source.type;
		this.fileSize = source.size;

		if (typeof window.FileReader !== 'function')
			throw new Error('Incompatible browser');

		this._fileReader = new FileReader();
		this._fileReader.addEventListener('load', function () {
			this.binFile._u8array = new Uint8Array(this.result);

			if (typeof onLoad === 'function')
				onLoad(this.binFile);
		}, false);


		this._fileReader.binFile = this;

		this._fileReader.readAsArrayBuffer(source);



	} else if (BinFile.RUNTIME_ENVIROMENT === 'node' && typeof source === 'string') {
		if (!nodeFs.existsSync(source))
			throw new Error(source + ' does not exist');

		const arrayBuffer = nodeFs.readFileSync(source);

		this.fileName = nodePath.basename(source);
		this.fileType = nodeFs.statSync(source).type;
		this.fileSize = arrayBuffer.byteLength;

		this._u8array = new Uint8Array(arrayBuffer);

		if (typeof onLoad === 'function')
			onLoad(this);



	} else if (source instanceof BinFile) { /* if source is another BinFile, clone it */
		this.fileName = source.fileName;
		this.fileType = source.fileType;
		this.fileSize = source.fileSize;

		this._u8array = new Uint8Array(source._u8array.buffer.slice());

		if (typeof onLoad === 'function')
			onLoad(this);



	} else if (source instanceof ArrayBuffer) {
		this.fileName = 'file.bin';
		this.fileType = 'application/octet-stream';
		this.fileSize = source.byteLength;

		this._u8array = new Uint8Array(source);

		if (typeof onLoad === 'function')
			onLoad(this);



	} else if (ArrayBuffer.isView(source)) { /* source is TypedArray */
		this.fileName = 'file.bin';
		this.fileType = 'application/octet-stream';
		this.fileSize = source.buffer.byteLength;

		this._u8array = new Uint8Array(source.buffer);

		if (typeof onLoad === 'function')
			onLoad(this);



	} else if (typeof source === 'number') { /* source is integer, create new empty file */
		this.fileName = 'file.bin';
		this.fileType = 'application/octet-stream';
		this.fileSize = source;

		this._u8array = new Uint8Array(new ArrayBuffer(source));

		if (typeof onLoad === 'function')
			onLoad(this);



	} else {
		throw new Error('invalid BinFile source');
	}
}
BinFile.RUNTIME_ENVIROMENT = (function () {
	if (typeof window === 'object' && typeof window.document === 'object')
		return 'browser';
	else if (typeof WorkerGlobalScope === 'function' && self instanceof WorkerGlobalScope)
		return 'webworker';
	else if (typeof require === 'function' && typeof process === 'object' && typeof process.versions === 'object' && typeof process.versions.node === 'string')
		return 'node';
	else
		return null;
}());
BinFile.DEVICE_LITTLE_ENDIAN = (function () {	/* https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/DataView#Endianness */
	var buffer = new ArrayBuffer(2);
	new DataView(buffer).setInt16(0, 256, true /* littleEndian */);
	// Int16Array uses the platform's endianness.
	return new Int16Array(buffer)[0] === 256;
})();



BinFile.prototype.push = function () {
	this._offsetsStack.push(this.offset);
}
BinFile.prototype.pop = function () {
	this.seek(this._offsetsStack.pop());
}
BinFile.prototype.seek = function (offset) {
	this.offset = offset;
}
BinFile.prototype.skip = function (nBytes) {
	this.offset += nBytes;
}
BinFile.prototype.isEOF = function () {
	return !(this.offset < this.fileSize)
}
BinFile.prototype.slice = function (offset, len, doNotClone) {
	if (typeof offset !== 'number' || offset < 0)
		offset = 0;
	else if (offset >= this.fileSize)
		throw new Error('out of bounds slicing');
	else
		offset = Math.floor(offset);

	if (typeof len !== 'number' || offset < 0 || (offset + len) >= this.fileSize.length)
		len = this.fileSize - offset;
	else if (len === 0)
		throw new Error('zero length provided for slicing');
	else
		len = Math.floor(len);

	if (offset === 0 && len === this.fileSize && doNotClone)
		return this;


	var newFile = new BinFile(this._u8array.buffer.slice(offset, offset + len));
	newFile.fileName = this.fileName;
	newFile.fileType = this.fileType;
	newFile.littleEndian = this.littleEndian;
	return newFile;
}
BinFile.prototype.prependBytes = function (bytes) {
	var newFile = new BinFile(this.fileSize + bytes.length);
	newFile.seek(0);
	newFile.writeBytes(bytes);
	this.copyTo(newFile, 0, this.fileSize, bytes.length);

	this.fileSize = newFile.fileSize;
	this._u8array = newFile._u8array;
	return this;
}
BinFile.prototype.removeLeadingBytes = function (nBytes) {
	this.seek(0);
	var oldData = this.readBytes(nBytes);
	var newFile = this.slice(nBytes.length);

	this.fileSize = newFile.fileSize;
	this._u8array = newFile._u8array;
	return oldData;
}


BinFile.prototype.copyTo = function (target, offsetSource, len, offsetTarget) {
	if (!(target instanceof BinFile))
		throw new Error('target is not a BinFile object');

	if (typeof offsetTarget !== 'number')
		offsetTarget = offsetSource;

	len = len || (this.fileSize - offsetSource);

	for (var i = 0; i < len; i++) {
		target._u8array[offsetTarget + i] = this._u8array[offsetSource + i];
	}
}


BinFile.prototype.save = function () {
	if (BinFile.RUNTIME_ENVIROMENT === 'browser') {
		var fileBlob = new Blob([this._u8array], { type: this.fileType });
		var blobUrl = URL.createObjectURL(fileBlob);
		var a = document.createElement('a');
		a.href = blobUrl;
		a.download = this.fileName;
		document.body.appendChild(a);
		a.dispatchEvent(new MouseEvent('click'));
		URL.revokeObjectURL(blobUrl);
		document.body.removeChild(a);
	} else if (BinFile.RUNTIME_ENVIROMENT === 'node') {
		nodeFs.writeFileSync(this.fileName, Buffer.from(this._u8array.buffer));
	} else {
		throw new Error('invalid runtime environment, can\'t save file');
	}
}


BinFile.prototype.getExtension = function () {
	var ext = this.fileName ? this.fileName.toLowerCase().match(/\.(\w+)$/) : '';

	return ext ? ext[1] : '';
}
BinFile.prototype.getName = function () {
	return this.fileName.replace(new RegExp('\\.' + this.getExtension() + '$', 'i'), '');
}
BinFile.prototype.setExtension = function (newExtension) {
	return (this.fileName = this.getName() + '.' + newExtension);
}
BinFile.prototype.setName = function (newName) {
	return (this.fileName = newName + '.' + this.getExtension());
}


BinFile.prototype.readU8 = function () {
	this._lastRead = this._u8array[this.offset++];

	return this._lastRead
}
BinFile.prototype.readU16 = function () {
	if (this.littleEndian)
		this._lastRead = this._u8array[this.offset] + (this._u8array[this.offset + 1] << 8);
	else
		this._lastRead = (this._u8array[this.offset] << 8) + this._u8array[this.offset + 1];

	this.offset += 2;
	return this._lastRead >>> 0
}
BinFile.prototype.readU24 = function () {
	if (this.littleEndian)
		this._lastRead = this._u8array[this.offset] + (this._u8array[this.offset + 1] << 8) + (this._u8array[this.offset + 2] << 16);
	else
		this._lastRead = (this._u8array[this.offset] << 16) + (this._u8array[this.offset + 1] << 8) + this._u8array[this.offset + 2];

	this.offset += 3;
	return this._lastRead >>> 0
}
BinFile.prototype.readU32 = function () {
	if (this.littleEndian)
		this._lastRead = this._u8array[this.offset] + (this._u8array[this.offset + 1] << 8) + (this._u8array[this.offset + 2] << 16) + (this._u8array[this.offset + 3] << 24);
	else
		this._lastRead = (this._u8array[this.offset] << 24) + (this._u8array[this.offset + 1] << 16) + (this._u8array[this.offset + 2] << 8) + this._u8array[this.offset + 3];

	this.offset += 4;
	return this._lastRead >>> 0
}
BinFile.prototype.readU64 = function () {
	if (this.littleEndian)
		this._lastRead = this._u8array[this.offset] + (this._u8array[this.offset + 1] << 8) + (this._u8array[this.offset + 2] << 16) + (this._u8array[this.offset + 3] << 24) + (this._u8array[this.offset + 4] << 32) + (this._u8array[this.offset + 5] << 40) + (this._u8array[this.offset + 6] << 48) + (this._u8array[this.offset + 7] << 56);
	else
		this._lastRead = (this._u8array[this.offset] << 56) + (this._u8array[this.offset + 1] << 48) + (this._u8array[this.offset + 2] << 40) + (this._u8array[this.offset + 3] << 32) + (this._u8array[this.offset + 4] << 24) + (this._u8array[this.offset + 5] << 16) + (this._u8array[this.offset + 6] << 8) + this._u8array[this.offset + 7];
	this.offset += 8;
	return this._lastRead >>> 0
}



BinFile.prototype.readBytes = function (len) {
	this._lastRead = new Array(len);
	for (var i = 0; i < len; i++) {
		this._lastRead[i] = this._u8array[this.offset + i];
	}

	this.offset += len;
	return this._lastRead
}

BinFile.prototype.readString = function (len) {
	this._lastRead = '';
	for (var i = 0; i < len && (this.offset + i) < this.fileSize && this._u8array[this.offset + i] > 0; i++)
		this._lastRead = this._lastRead + String.fromCharCode(this._u8array[this.offset + i]);

	this.offset += len;
	return this._lastRead
}

BinFile.prototype.writeU8 = function (u8) {
	this._u8array[this.offset++] = u8;
}
BinFile.prototype.writeU16 = function (u16) {
	if (this.littleEndian) {
		this._u8array[this.offset] = u16 & 0xff;
		this._u8array[this.offset + 1] = u16 >> 8;
	} else {
		this._u8array[this.offset] = u16 >> 8;
		this._u8array[this.offset + 1] = u16 & 0xff;
	}

	this.offset += 2;
}
BinFile.prototype.writeU24 = function (u24) {
	if (this.littleEndian) {
		this._u8array[this.offset] = u24 & 0x0000ff;
		this._u8array[this.offset + 1] = (u24 & 0x00ff00) >> 8;
		this._u8array[this.offset + 2] = (u24 & 0xff0000) >> 16;
	} else {
		this._u8array[this.offset] = (u24 & 0xff0000) >> 16;
		this._u8array[this.offset + 1] = (u24 & 0x00ff00) >> 8;
		this._u8array[this.offset + 2] = u24 & 0x0000ff;
	}

	this.offset += 3;
}
BinFile.prototype.writeU32 = function (u32) {
	if (this.littleEndian) {
		this._u8array[this.offset] = u32 & 0x000000ff;
		this._u8array[this.offset + 1] = (u32 & 0x0000ff00) >> 8;
		this._u8array[this.offset + 2] = (u32 & 0x00ff0000) >> 16;
		this._u8array[this.offset + 3] = (u32 & 0xff000000) >> 24;
	} else {
		this._u8array[this.offset] = (u32 & 0xff000000) >> 24;
		this._u8array[this.offset + 1] = (u32 & 0x00ff0000) >> 16;
		this._u8array[this.offset + 2] = (u32 & 0x0000ff00) >> 8;
		this._u8array[this.offset + 3] = u32 & 0x000000ff;
	}

	this.offset += 4;
}


BinFile.prototype.writeBytes = function (a) {
	for (var i = 0; i < a.length; i++)
		this._u8array[this.offset + i] = a[i]

	this.offset += a.length;
}

BinFile.prototype.writeString = function (str, len) {
	len = len || str.length;
	for (var i = 0; i < str.length && i < len; i++)
		this._u8array[this.offset + i] = str.charCodeAt(i);

	for (; i < len; i++)
		this._u8array[this.offset + i] = 0x00;

	this.offset += len;
}


BinFile.prototype.swapBytes = function (swapSize, newFile) {
	if (typeof swapSize !== 'number') {
		swapSize = 4;
	}

	if (this.fileSize % swapSize !== 0) {
		throw new Error('file size is not divisible by ' + swapSize);
	}

	var swappedFile = new BinFile(this.fileSize);
	this.seek(0);
	while (!this.isEOF()) {
		swappedFile.writeBytes(
			this.readBytes(swapSize).reverse()
		);
	}

	if (newFile) {
		swappedFile.fileName = this.fileName;
		swappedFile.fileType = this.fileType;

		return swappedFile;
	} else {
		this._u8array = swappedFile._u8array;

		return this;
	}

}





BinFile.prototype.hashSHA1 = async function (start, len) {
	if (typeof HashCalculator !== 'object' || typeof HashCalculator.sha1 !== 'function')
		throw new Error('no Hash object found or missing sha1 function');

	return HashCalculator.sha1(this.slice(start, len, true)._u8array.buffer);
}
BinFile.prototype.hashMD5 = function (start, len) {
	if (typeof HashCalculator !== 'object' || typeof HashCalculator.md5 !== 'function')
		throw new Error('no Hash object found or missing md5 function');

	return HashCalculator.md5(this.slice(start, len, true)._u8array.buffer);
}
BinFile.prototype.hashCRC32 = function (start, len) {
	if (typeof HashCalculator !== 'object' || typeof HashCalculator.crc32 !== 'function')
		throw new Error('no Hash object found or missing crc32 function');

	return HashCalculator.crc32(this.slice(start, len, true)._u8array.buffer);
}
BinFile.prototype.hashAdler32 = function (start, len) {
	if (typeof HashCalculator !== 'object' || typeof HashCalculator.adler32 !== 'function')
		throw new Error('no Hash object found or missing adler32 function');

	return HashCalculator.adler32(this.slice(start, len, true)._u8array.buffer);
}
BinFile.prototype.hashCRC16 = function (start, len) {
	if (typeof HashCalculator !== 'object' || typeof HashCalculator.crc16 !== 'function')
		throw new Error('no Hash object found or missing crc16 function');

	return HashCalculator.crc16(this.slice(start, len, true)._u8array.buffer);
}















if (BinFile.RUNTIME_ENVIROMENT === 'node' && typeof module !== 'undefined' && module.exports) {
	module.exports = BinFile;
	HashCalculator = require('./HashCalculator');
	nodePath = require('path');
	nodeFs = require('fs');
}

/* ---- modules/RomPatcher.format.ips.js ---- */
/* IPS module for Rom Patcher JS v20250430 - Marc Robledo 2016-2025 - http://www.marcrobledo.com/license */
/* File format specification: http://www.smwiki.net/wiki/IPS_file_format */

/* This file also acts as EBP (EarthBound Patch) module */
/* EBP is actually just IPS with some JSON metadata stuck on the end (implementation: https://github.com/Lyrositor/EBPatcher) */

const IPS_MAGIC='PATCH';
const IPS_MAX_ROM_SIZE=0x1000000; //16 megabytes
const IPS_RECORD_RLE=0x0000;
const IPS_RECORD_SIMPLE=0x01;


if(typeof module !== "undefined" && module.exports){
	module.exports = IPS;
}


function IPS(){
	this.records=[];
	this.truncate=false;
	this.EBPmetadata=null;
}
IPS.prototype.addSimpleRecord=function(o, d){
	this.records.push({offset:o, type:IPS_RECORD_SIMPLE, length:d.length, data:d})
}
IPS.prototype.addRLERecord=function(o, l, b){
	this.records.push({offset:o, type:IPS_RECORD_RLE, length:l, byte:b})
}
IPS.prototype.setEBPMetadata=function(metadataObject){
	if(typeof metadataObject !== 'object')
		throw new TypeError('metadataObject must be an object');
	for(var key in metadataObject){
		if(typeof metadataObject[key] !== 'string')
			throw new TypeError('metadataObject values must be strings');
	}

	/* EBPatcher (linked above) expects the "patcher" field to be EBPatcher to read the metadata */
	/* CoilSnake (EB modding tool) inserts this manually too */
	/* So we also add it here for compatibility purposes */
	this.EBPmetadata={patcher:'EBPatcher', ...metadataObject};
}
IPS.prototype.getDescription=function(){
	if(this.EBPmetadata){
		var description='';
		for(var key in this.EBPmetadata){
			if(key==='patcher')
				continue;

			const keyPretty=key.charAt(0).toUpperCase() + key.slice(1);
			description+=keyPretty+': '+this.EBPmetadata[key]+'\n';
		}
		return description.trim();
	}
	return null;
}
IPS.prototype.toString=function(){
	nSimpleRecords=0;
	nRLERecords=0;
	for(var i=0; i<this.records.length; i++){
		if(this.records[i].type===IPS_RECORD_RLE)
			nRLERecords++;
		else
			nSimpleRecords++;
	}
	var s='Simple records: '+nSimpleRecords;
	s+='\nRLE records: '+nRLERecords;
	s+='\nTotal records: '+this.records.length;
	if(this.truncate && !this.EBPmetadata)
		s+='\nTruncate at: 0x'+this.truncate.toString(16);
	else if(this.EBPmetadata)
		s+='\nEBP Metadata: '+JSON.stringify(this.EBPmetadata);
	return s
}
IPS.prototype.export=function(fileName){
	var patchFileSize=5; //PATCH string
	for(var i=0; i<this.records.length; i++){
		if(this.records[i].type===IPS_RECORD_RLE)
			patchFileSize+=(3+2+2+1); //offset+0x0000+length+RLE byte to be written
		else
			patchFileSize+=(3+2+this.records[i].data.length); //offset+length+data
	}
	patchFileSize+=3; //EOF string
	if(this.truncate && !this.EBPmetadata)
		patchFileSize+=3; //truncate
	else if(this.EBPmetadata)
		patchFileSize+=JSON.stringify(this.EBPmetadata).length;

	tempFile=new BinFile(patchFileSize);
	tempFile.fileName=fileName+(this.EBPmetadata? '.ebp' : '.ips');
	tempFile.writeString(IPS_MAGIC);
	for(var i=0; i<this.records.length; i++){
		var rec=this.records[i];
		tempFile.writeU24(rec.offset);
		if(rec.type===IPS_RECORD_RLE){
			tempFile.writeU16(0x0000);
			tempFile.writeU16(rec.length);
			tempFile.writeU8(rec.byte);
		}else{
			tempFile.writeU16(rec.data.length);
			tempFile.writeBytes(rec.data);
		}
	}

	tempFile.writeString('EOF');
	if(this.truncate && !this.EBPmetadata)
		tempFile.writeU24(this.truncate);
	else if(this.EBPmetadata)
		tempFile.writeString(JSON.stringify(this.EBPmetadata));

	return tempFile
}
IPS.prototype.apply=function(romFile){
	if(this.truncate && !this.EBPmetadata){
		if(this.truncate>romFile.fileSize){ //expand (discussed here: https://github.com/marcrobledo/RomPatcher.js/pull/46)
			tempFile=new BinFile(this.truncate);
			romFile.copyTo(tempFile, 0, romFile.fileSize, 0);
		}else{ //truncate
			tempFile=romFile.slice(0, this.truncate);
		}
	}else{
		//calculate target ROM size, expanding it if any record offset is beyond target ROM size
		var newFileSize=romFile.fileSize;
		for(var i=0; i<this.records.length; i++){
			var rec=this.records[i];
			if(rec.type===IPS_RECORD_RLE){
				if(rec.offset+rec.length>newFileSize){
					newFileSize=rec.offset+rec.length;
				}
			}else{
				if(rec.offset+rec.data.length>newFileSize){
					newFileSize=rec.offset+rec.data.length;
				}
			}
		}

		if(newFileSize===romFile.fileSize){
			tempFile=romFile.slice(0, romFile.fileSize);
		}else{
			tempFile=new BinFile(newFileSize);
			romFile.copyTo(tempFile,0);
		}
	}


	romFile.seek(0);

	for(var i=0; i<this.records.length; i++){
		tempFile.seek(this.records[i].offset);
		if(this.records[i].type===IPS_RECORD_RLE){
			for(var j=0; j<this.records[i].length; j++)
				tempFile.writeU8(this.records[i].byte);
		}else{
			tempFile.writeBytes(this.records[i].data);
		} 
	}

	return tempFile
}

IPS.MAGIC=IPS_MAGIC;


IPS.fromFile=function(file){
	var patchFile=new IPS();
	file.seek(5);

	while(!file.isEOF()){
		var offset=file.readU24();

		if(offset===0x454f46){ /* EOF */
			if(file.isEOF()){
				break;
			}else if((file.offset+3)===file.fileSize){
				patchFile.truncate=file.readU24();
				break;
			}else if (file.readU8()==='{'.charCodeAt(0)) {
				file.skip(-1);
				patchFile.setEBPMetadata(JSON.parse(file.readString(file.fileSize-file.offset)));
				break;
			}
		}

		var length=file.readU16();

		if(length===IPS_RECORD_RLE){
			patchFile.addRLERecord(offset, file.readU16(), file.readU8());
		}else{
			patchFile.addSimpleRecord(offset, file.readBytes(length));
		}
	}
	return patchFile;
}


IPS.buildFromRoms=function(original, modified, asEBP=false){
	var patch=new IPS();

	if(!asEBP && modified.fileSize<original.fileSize){
		patch.truncate=modified.fileSize;
	}else if(asEBP){
		patch.setEBPMetadata(typeof asEBP==='object'? asEBP : {
			'Author':'Unknown',
			'Title':'Untitled',
			'Description':'No description',
		});
	}

	//solucion: guardar startOffset y endOffset (ir mirando de 6 en 6 hacia atrás)
	var previousRecord={type:0xdeadbeef,startOffset:0,length:0};
	while(!modified.isEOF()){
		var b1=original.isEOF()?0x00:original.readU8();
		var b2=modified.readU8();

		if(b1!==b2){
			var RLEmode=true;
			var differentData=[];
			var startOffset=modified.offset-1;

			while(b1!==b2 && differentData.length<0xffff){
				differentData.push(b2);
				if(b2!==differentData[0])
					RLEmode=false;

				if(modified.isEOF() || differentData.length===0xffff)
					break;

				b1=original.isEOF()?0x00:original.readU8();
				b2=modified.readU8();
			}


			//check if this record is near the previous one
			var distance=startOffset-(previousRecord.offset+previousRecord.length);
			if(
				previousRecord.type===IPS_RECORD_SIMPLE &&
				distance<6 && (previousRecord.length+distance+differentData.length)<0xffff
			){
				if(RLEmode && differentData.length>6){
					// separate a potential RLE record
					original.seek(startOffset);
					modified.seek(startOffset);
					previousRecord={type:0xdeadbeef,startOffset:0,length:0};
				}else{
					// merge both records
					while(distance--){
						previousRecord.data.push(modified._u8array[previousRecord.offset+previousRecord.length]);
						previousRecord.length++;
					}
					previousRecord.data=previousRecord.data.concat(differentData);
					previousRecord.length=previousRecord.data.length;
				}
			}else{
				if(startOffset>=IPS_MAX_ROM_SIZE){
					throw new Error(`Files are too big for ${patch.EBPmetadata? 'EBP' : 'IPS'} format`);
					return null;
				}

				if(RLEmode && differentData.length>2){
					patch.addRLERecord(startOffset, differentData.length, differentData[0]);
				}else{
					patch.addSimpleRecord(startOffset, differentData);
				}
				previousRecord=patch.records[patch.records.length-1];
			}
		}
	}




	if(modified.fileSize>original.fileSize){
		var lastRecord=patch.records[patch.records.length-1];
		var lastOffset=lastRecord.offset+lastRecord.length;

		if(lastOffset<modified.fileSize){
			patch.addSimpleRecord(modified.fileSize-1, [0x00]);
		}
	}


	return patch
}
/* ---- modules/RomPatcher.format.ups.js ---- */
/* UPS module for Rom Patcher JS v20240721 - Marc Robledo 2017-2024 - http://www.marcrobledo.com/license */
/* File format specification: http://www.romhacking.net/documents/392/ */

const UPS_MAGIC='UPS1';
if(typeof module !== "undefined" && module.exports){
	module.exports = UPS;
}
function UPS(){
	this.records=[];
	this.sizeInput=0;
	this.sizeOutput=0;
	this.checksumInput=0;
	this.checksumOutput=0;
}
UPS.prototype.addRecord=function(relativeOffset, d){
	this.records.push({offset:relativeOffset, XORdata:d})
}
UPS.prototype.toString=function(){
	var s='Records: '+this.records.length;
	s+='\nInput file size: '+this.sizeInput;
	s+='\nOutput file size: '+this.sizeOutput;
	s+='\nInput file checksum: '+this.checksumInput.toString(16);
	s+='\nOutput file checksum: '+this.checksumOutput.toString(16);
	return s
}
UPS.prototype.export=function(fileName){
	var patchFileSize=UPS_MAGIC.length;//UPS1 string
	patchFileSize+=UPS_getVLVLength(this.sizeInput); //input file size
	patchFileSize+=UPS_getVLVLength(this.sizeOutput); //output file size
	for(var i=0; i<this.records.length; i++){
		patchFileSize+=UPS_getVLVLength(this.records[i].offset);
		patchFileSize+=this.records[i].XORdata.length+1;
	}
	patchFileSize+=12; //input/output/patch checksums

	tempFile=new BinFile(patchFileSize);
	tempFile.writeVLV=UPS_writeVLV;
	tempFile.fileName=fileName+'.ups';
	tempFile.writeString(UPS_MAGIC);

	tempFile.writeVLV(this.sizeInput);
	tempFile.writeVLV(this.sizeOutput);

	for(var i=0; i<this.records.length; i++){
		tempFile.writeVLV(this.records[i].offset);
		tempFile.writeBytes(this.records[i].XORdata);
		tempFile.writeU8(0x00);
	}
	tempFile.littleEndian=true;
	tempFile.writeU32(this.checksumInput);
	tempFile.writeU32(this.checksumOutput);
	tempFile.writeU32(tempFile.hashCRC32(0, tempFile.fileSize - 4));

	return tempFile
}
UPS.prototype.validateSource=function(romFile,headerSize){return romFile.hashCRC32(headerSize)===this.checksumInput}
UPS.prototype.getValidationInfo=function(){
	return {
		'type':'CRC32',
		'value':this.checksumInput
	};
}
UPS.prototype.apply=function(romFile, validate){
	if(validate && !this.validateSource(romFile)){
		throw new Error('Source ROM checksum mismatch');
	}

	/* fix the glitch that cut the end of the file if it's larger than the changed file patch was originally created with */
	/* more info: https://github.com/marcrobledo/RomPatcher.js/pull/40#issuecomment-1069087423 */
	sizeOutput = this.sizeOutput;
	sizeInput = this.sizeInput;
	if(!validate && sizeInput < romFile.fileSize){
		sizeInput = romFile.fileSize;
		if(sizeOutput < sizeInput){
			sizeOutput = sizeInput;
		}
	}

	/* copy original file */
	tempFile=new BinFile(sizeOutput);
	romFile.copyTo(tempFile, 0, sizeInput);

	romFile.seek(0);


	var nextOffset=0;
	for(var i=0; i<this.records.length; i++){
		var record=this.records[i];
		tempFile.skip(record.offset);
		romFile.skip(record.offset);

		for(var j=0; j<record.XORdata.length; j++){
			tempFile.writeU8((romFile.isEOF()?0x00:romFile.readU8()) ^ record.XORdata[j]);
		}
		tempFile.skip(1);
		romFile.skip(1);
	}

	if(validate && tempFile.hashCRC32()!==this.checksumOutput){
		throw new Error('Target ROM checksum mismatch');
	}

	return tempFile
}

UPS.MAGIC=UPS_MAGIC;


/* encode/decode variable length values, used by UPS file structure */
function UPS_writeVLV(data){
	while(1){
		var x=data & 0x7f;
		data=data>>7;
		if(data===0){
			this.writeU8(0x80 | x);
			break;
		}
		this.writeU8(x);
		data=data-1;
	}
}
function UPS_readVLV(){
	var data=0;

	var shift=1;
	while(1){
		var x=this.readU8();

		if(x==-1)
			throw new Error('Can\'t read UPS VLV at 0x'+(this.offset-1).toString(16));

		data+=(x&0x7f)*shift;
		if((x&0x80)!==0)
			break;
		shift=shift<<7;
		data+=shift;
	}
	return data
}
function UPS_getVLVLength(data){
	var len=0;
	while(1){
		var x=data & 0x7f;
		data=data>>7;
		len++;
		if(data===0){
			break;
		}
		data=data-1;
	}
	return len;
}


UPS.fromFile=function(file){
	var patch=new UPS();
	file.readVLV=UPS_readVLV;

	file.seek(UPS_MAGIC.length);

	patch.sizeInput=file.readVLV();
	patch.sizeOutput=file.readVLV();


	var nextOffset=0;
	while(file.offset<(file.fileSize-12)){
		var relativeOffset=file.readVLV();


		var XORdifferences=[];
		while(file.readU8()){
			XORdifferences.push(file._lastRead);
		}
		patch.addRecord(relativeOffset, XORdifferences);
	}

	file.littleEndian=true;
	patch.checksumInput=file.readU32();
	patch.checksumOutput=file.readU32();

	if(file.readU32()!==file.hashCRC32(0, file.fileSize - 4)){
		throw new Error('Patch checksum mismatch');
	}

	file.littleEndian=false;
	return patch;
}



UPS.buildFromRoms=function(original, modified){
	var patch=new UPS();
	patch.sizeInput=original.fileSize;
	patch.sizeOutput=modified.fileSize;


	var previousSeek=1;
	while(!modified.isEOF()){
		var b1=original.isEOF()?0x00:original.readU8();
		var b2=modified.readU8();

		if(b1!==b2){
			var currentSeek=modified.offset;
			var XORdata=[];

			while(b1!==b2){
				XORdata.push(b1 ^ b2);

				if(modified.isEOF())
					break;
				b1=original.isEOF()?0x00:original.readU8();
				b2=modified.readU8();
			}

			patch.addRecord(currentSeek-previousSeek, XORdata);
			previousSeek=currentSeek+XORdata.length+1;
		}
	}


	patch.checksumInput=original.hashCRC32();
	patch.checksumOutput=modified.hashCRC32();
	return patch
}
/* ---- modules/RomPatcher.format.aps_n64.js ---- */
/* APS (N64) module for Rom Patcher JS v20180930 - Marc Robledo 2017-2018 - http://www.marcrobledo.com/license */
/* File format specification: https://github.com/btimofeev/UniPatcher/wiki/APS-(N64) */

const APS_N64_MAGIC='APS10';
const APS_RECORD_RLE=0x0000;
const APS_RECORD_SIMPLE=0x01;
const APS_N64_MODE=0x01;
if(typeof module !== "undefined" && module.exports){
	module.exports = APS;
}
function APS(){
	this.records=[];
	this.headerType=0;
	this.encodingMethod=0;
	this.description='no description';

	this.header={};
}
APS.prototype.addRecord=function(o, d){
	this.records.push({offset:o, type:APS_RECORD_SIMPLE, data:d})
}
APS.prototype.addRLERecord=function(o, b, l){
	this.records.push({offset:o, type:APS_RECORD_RLE, length:l, byte:b})
}
APS.prototype.toString=function(){
	var s='Total records: '+this.records.length;
	s+='\nHeader type: '+this.headerType;
	if(this.headerType===APS_N64_MODE){
		s+=' (N64)';
	}
	s+='\nEncoding method: '+this.encodingMethod;
	s+='\nDescription: '+this.description;
	s+='\nHeader: '+JSON.stringify(this.header);
	return s
}
APS.prototype.validateSource=function(sourceFile){
	if(this.headerType===APS_N64_MODE){
		sourceFile.seek(0x3c);
		if(sourceFile.readString(3)!==this.header.cartId)
			return false;

		sourceFile.seek(0x10);
		var crc=sourceFile.readBytes(8);
		for(var i=0; i<8; i++){
			if(crc[i]!==this.header.crc[i])
				return false
		}
	}
	return true
}
APS.prototype.getValidationInfo=function(){
	if(this.headerType===APS_N64_MODE){
		return this.header.cartId + ' (' + this.header.crc.reduce(function(hex, b){
				if(b<16)
					return hex + '0' + b.toString(16);
				else
					return hex + b.toString(16);
			}, '') + ')';
	}
	return null;
}
APS.prototype.export=function(fileName){
	var patchFileSize=61;
	if(this.headerType===APS_N64_MODE)
		patchFileSize+=17;

	for(var i=0; i<this.records.length; i++){
		if(this.records[i].type===APS_RECORD_RLE)
			patchFileSize+=7;
		else
			patchFileSize+=5+this.records[i].data.length; //offset+length+data
	}

	tempFile=new BinFile(patchFileSize);
	tempFile.littleEndian=true;
	tempFile.fileName=fileName+'.aps';
	tempFile.writeString(APS_N64_MAGIC, APS_N64_MAGIC.length);
	tempFile.writeU8(this.headerType);
	tempFile.writeU8(this.encodingMethod);
	tempFile.writeString(this.description, 50);

	if(this.headerType===APS_N64_MODE){
		tempFile.writeU8(this.header.originalN64Format);
		tempFile.writeString(this.header.cartId, 3);
		tempFile.writeBytes(this.header.crc);
		tempFile.writeBytes(this.header.pad);
	}
	tempFile.writeU32(this.header.sizeOutput);

	for(var i=0; i<this.records.length; i++){
		var rec=this.records[i];
		tempFile.writeU32(rec.offset);
		if(rec.type===APS_RECORD_RLE){
			tempFile.writeU8(0x00);
			tempFile.writeU8(rec.byte);
			tempFile.writeU8(rec.length);
		}else{
			tempFile.writeU8(rec.data.length);
			tempFile.writeBytes(rec.data);
		}
	}

	return tempFile
}

APS.prototype.apply=function(romFile, validate){
	if(validate && !this.validateSource(romFile)){
		throw new Error('Source ROM checksum mismatch');
	}

	tempFile=new BinFile(this.header.sizeOutput);
	romFile.copyTo(tempFile, 0, tempFile.fileSize);

	for(var i=0; i<this.records.length; i++){
		tempFile.seek(this.records[i].offset);
		if(this.records[i].type===APS_RECORD_RLE){
			for(var j=0; j<this.records[i].length; j++)
				tempFile.writeU8(this.records[i].byte);
		}else{
			tempFile.writeBytes(this.records[i].data);
		}
	}

	return tempFile
}

APS.MAGIC=APS_N64_MAGIC;



APS.fromFile=function(patchFile){
	var patch=new APS();
	patchFile.littleEndian=true;

	patchFile.seek(5);
	patch.headerType=patchFile.readU8();
	patch.encodingMethod=patchFile.readU8();
	patch.description=patchFile.readString(50);

	var seek;
	if(patch.headerType===APS_N64_MODE){
		patch.header.originalN64Format=patchFile.readU8();
		patch.header.cartId=patchFile.readString(3);
		patch.header.crc=patchFile.readBytes(8);
		patch.header.pad=patchFile.readBytes(5);
	}
	patch.header.sizeOutput=patchFile.readU32();

	while(!patchFile.isEOF()){
		var offset=patchFile.readU32();
		var length=patchFile.readU8();

		if(length===APS_RECORD_RLE)
			patch.addRLERecord(offset, patchFile.readU8(seek), patchFile.readU8(seek+1));
		else
			patch.addRecord(offset, patchFile.readBytes(length));
	}
	return patch;
}

APS.buildFromRoms=function(original, modified){
	var patch=new APS();



	if(original.readU32()===0x80371240){ //is N64 ROM
		patch.headerType=APS_N64_MODE;

		patch.header.originalN64Format=/\.v64$/i.test(original.fileName)?0:1;
		original.seek(0x3c);
		patch.header.cartId=original.readString(3);
		original.seek(0x10);
		patch.header.crc=original.readBytes(8);
		patch.header.pad=[0,0,0,0,0];
	}
	patch.header.sizeOutput=modified.fileSize;

	original.seek(0);
	modified.seek(0);

	while(!modified.isEOF()){
		var b1=original.isEOF()?0x00:original.readU8();
		var b2=modified.readU8();

		if(b1!==b2){
			var RLERecord=true;
			var differentBytes=[];
			var offset=modified.offset-1;

			while(b1!==b2 && differentBytes.length<0xff){
				differentBytes.push(b2);
				if(b2!==differentBytes[0])
					RLERecord=false;

				if(modified.isEOF() || differentBytes.length===0xff)
					break;

				b1=original.isEOF()?0x00:original.readU8();
				b2=modified.readU8();
			}

			if(RLERecord && differentBytes.length>2){
				patch.addRLERecord(offset, differentBytes[0], differentBytes.length);
			}else{
				patch.addRecord(offset, differentBytes);
			}
		}
	}

	return patch
}
/* ---- modules/RomPatcher.format.aps_gba.js ---- */
/* APS (GBA) module for Rom Patcher JS v20230331 - Marc Robledo 2017-2023 - http://www.marcrobledo.com/license */
/* File format specification: https://github.com/btimofeev/UniPatcher/wiki/APS-(GBA) */

const APS_GBA_MAGIC='APS1';
const APS_GBA_BLOCK_SIZE=0x010000; //64Kb
const APS_GBA_RECORD_SIZE=4 + 2 + 2 + APS_GBA_BLOCK_SIZE;
if(typeof module !== "undefined" && module.exports){
	module.exports = APSGBA;
}
function APSGBA(){
	this.sourceSize=0;
	this.targetSize=0;
	this.records=[];
}
APSGBA.prototype.addRecord=function(offset, sourceCrc16, targetCrc16, xorBytes){
	this.records.push({
		offset:offset,
		sourceCrc16:sourceCrc16,
		targetCrc16:targetCrc16,
		xorBytes:xorBytes}
	);
}
APSGBA.prototype.toString=function(){
	var s='Total records: '+this.records.length;
	s+='\nInput file size: '+this.sourceSize;
	s+='\nOutput file size: '+this.targetSize;
	return s
}
APSGBA.prototype.validateSource=function(sourceFile){
	if(sourceFile.fileSize!==this.sourceSize)
		return false;

	for(var i=0; i<this.records.length; i++){
		sourceFile.seek(this.records[i].offset);
		var bytes=sourceFile.readBytes(APS_GBA_BLOCK_SIZE);
		if(sourceFile.hashCRC16(this.records[i].offset, APS_GBA_BLOCK_SIZE) !== this.records[i].sourceCrc16)
			return false
	}

	return true
}
APSGBA.prototype.export=function(fileName){
	var patchFileSize=12 + (this.records.length * APS_GBA_RECORD_SIZE);

	tempFile=new BinFile(patchFileSize);
	tempFile.littleEndian=true;
	tempFile.fileName=fileName+'.aps';
	tempFile.writeString(APS_GBA_MAGIC, APS_GBA_MAGIC.length);
	tempFile.writeU32(this.sourceSize);
	tempFile.writeU32(this.targetSize);

	for(var i=0; i<this.records.length; i++){
		tempFile.writeU32(this.records[i].offset);
		tempFile.writeU16(this.records[i].sourceCrc16);
		tempFile.writeU16(this.records[i].targetCrc16);
		tempFile.writeBytes(this.records[i].xorBytes);
	}

	return tempFile
}

APSGBA.prototype.apply=function(romFile, validate){
	if(validate && !this.validateSource(romFile)){
		throw new Error('Source ROM checksum mismatch');
	}

	tempFile=new BinFile(this.targetSize);
	romFile.copyTo(tempFile, 0, romFile.fileSize);

	for(var i=0; i<this.records.length; i++){
		romFile.seek(this.records[i].offset);
		tempFile.seek(this.records[i].offset);
		for(var j=0; j<APS_GBA_BLOCK_SIZE; j++){
			tempFile.writeU8(romFile.readU8() ^ this.records[i].xorBytes[j]);
		}

		if(validate && tempFile.hashCRC16(this.records[i].offset, APS_GBA_BLOCK_SIZE)!==this.records[i].targetCrc16){
			throw new Error('Target ROM checksum mismatch');
		}
	}



	return tempFile
}

APSGBA.MAGIC=APS_GBA_MAGIC;

APSGBA.fromFile=function(patchFile){
	patchFile.seek(0);
	patchFile.littleEndian=true;

	if(
		patchFile.readString(APS_GBA_MAGIC.length)!==APS_GBA_MAGIC ||
		patchFile.fileSize < (12 + APS_GBA_RECORD_SIZE) ||
		(patchFile.fileSize-12)%APS_GBA_RECORD_SIZE!==0
	)
		return null;

	var patch=new APSGBA();

	patch.sourceSize=patchFile.readU32();
	patch.targetSize=patchFile.readU32();

	while(!patchFile.isEOF()){
		var offset=patchFile.readU32();
		var sourceCrc16=patchFile.readU16();
		var targetCrc16=patchFile.readU16();
		var xorBytes=patchFile.readBytes(APS_GBA_BLOCK_SIZE);
		
		patch.addRecord(offset, sourceCrc16, targetCrc16, xorBytes);
	}
	return patch;
}

/* ---- modules/RomPatcher.format.bps.js ---- */
/* BPS module for Rom Patcher JS v20240821 - Marc Robledo 2016-2024 - http://www.marcrobledo.com/license */
/* File format specification: https://www.romhacking.net/documents/746/ */

const BPS_MAGIC='BPS1';
const BPS_ACTION_SOURCE_READ=0;
const BPS_ACTION_TARGET_READ=1;
const BPS_ACTION_SOURCE_COPY=2;
const BPS_ACTION_TARGET_COPY=3;
if(typeof module !== "undefined" && module.exports){
	module.exports = BPS;
}

function BPS(){
	this.sourceSize=0;
	this.targetSize=0;
	this.metaData='';
	this.actions=[];
	this.sourceChecksum=0;
	this.targetChecksum=0;
	this.patchChecksum=0;
}
BPS.prototype.toString=function(){
	var s='Source size: '+this.sourceSize;
	s+='\nTarget size: '+this.targetSize;
	s+='\nMetadata: '+this.metaData;
	s+='\n#Actions: '+this.actions.length;
	return s
}
BPS.prototype.calculateFileChecksum = function () {
	var patchFile = this.export();
	return patchFile.hashCRC32(0, patchFile.fileSize - 4);
}
BPS.prototype.validateSource=function(romFile,headerSize){return this.sourceChecksum===romFile.hashCRC32(headerSize)}
BPS.prototype.getValidationInfo=function(){
	return {
		'type':'CRC32',
		'value':this.sourceChecksum
	}
}
BPS.prototype.apply=function(romFile, validate){
	if(validate && !this.validateSource(romFile)){
		throw new Error('Source ROM checksum mismatch');
	}
	

	tempFile=new BinFile(this.targetSize);


	//patch
	var sourceRelativeOffset=0;
	var targetRelativeOffset=0;
	for(var i=0; i<this.actions.length; i++){
		var action=this.actions[i];

		if(action.type===BPS_ACTION_SOURCE_READ){
			romFile.copyTo(tempFile, tempFile.offset, action.length);
			tempFile.skip(action.length);

		}else if(action.type===BPS_ACTION_TARGET_READ){
			tempFile.writeBytes(action.bytes);

		}else if(action.type===BPS_ACTION_SOURCE_COPY){
			sourceRelativeOffset+=action.relativeOffset;
			var actionLength=action.length;
			while(actionLength--){
				tempFile.writeU8(romFile._u8array[sourceRelativeOffset]);
				sourceRelativeOffset++;
			}
		}else if(action.type===BPS_ACTION_TARGET_COPY){
			targetRelativeOffset+=action.relativeOffset;
			var actionLength=action.length;
			while(actionLength--) {
				tempFile.writeU8(tempFile._u8array[targetRelativeOffset]);
				targetRelativeOffset++;
			}
		}
	}

	if(validate && this.targetChecksum!==tempFile.hashCRC32()){
		throw new Error('Target ROM checksum mismatch');
	}

	return tempFile
}

BPS.MAGIC=BPS_MAGIC;


BPS.fromFile=function(file){
	file.readVLV=BPS_readVLV;

	file.littleEndian=true;
	var patch=new BPS();

	
	file.seek(4); //skip BPS1
	
	patch.sourceSize=file.readVLV();
	patch.targetSize=file.readVLV();

	var metaDataLength=file.readVLV();
	if(metaDataLength){
		patch.metaData=file.readString(metaDataLength);
	}


	var endActionsOffset=file.fileSize-12;
	while(file.offset<endActionsOffset){
		var data=file.readVLV();
		var action={type: data & 3, length: (data >> 2)+1};

		if(action.type===BPS_ACTION_TARGET_READ){
			action.bytes=file.readBytes(action.length);

		}else if(action.type===BPS_ACTION_SOURCE_COPY || action.type===BPS_ACTION_TARGET_COPY){
			var relativeOffset=file.readVLV();
			action.relativeOffset=(relativeOffset & 1? -1 : +1) * (relativeOffset >> 1)
		}

		patch.actions.push(action);
	}

	//file.seek(endActionsOffset);
	patch.sourceChecksum=file.readU32();
	patch.targetChecksum=file.readU32();
	patch.patchChecksum=file.readU32();

	if (patch.patchChecksum !== patch.calculateFileChecksum()) {
		throw new Error('Patch checksum mismatch');
	}


	return patch;
}



function BPS_readVLV(){
	var data=0, shift=1;
	while(true){
		var x = this.readU8();
		data += (x & 0x7f) * shift;
		if(x & 0x80)
			break;
		shift <<= 7;
		data += shift;
	}

	this._lastRead=data;
	return data;
}
function BPS_writeVLV(data){
	while(true){
		var x = data & 0x7f;
		data >>= 7;
		if(data === 0){
			this.writeU8(0x80 | x);
			break;
		}
		this.writeU8(x);
		data--;
	}
}
function BPS_getVLVLen(data){
	var len=0;
	while(true){
		var x = data & 0x7f;
		data >>= 7;
		if(data === 0){
			len++;
			break;
		}
		len++;
		data--;
	}
	return len;
}


BPS.prototype.export=function(fileName){
	var patchFileSize=BPS_MAGIC.length;	
	patchFileSize+=BPS_getVLVLen(this.sourceSize);
	patchFileSize+=BPS_getVLVLen(this.targetSize);
	patchFileSize+=BPS_getVLVLen(this.metaData.length);
	patchFileSize+=this.metaData.length;
	for(var i=0; i<this.actions.length; i++){
		var action=this.actions[i];
		patchFileSize+=BPS_getVLVLen(((action.length-1)<<2) + action.type);
	
		if(action.type===BPS_ACTION_TARGET_READ){
			patchFileSize+=action.length;
		}else if(action.type===BPS_ACTION_SOURCE_COPY || action.type===BPS_ACTION_TARGET_COPY){
			patchFileSize+=BPS_getVLVLen((Math.abs(action.relativeOffset)<<1)+(action.relativeOffset<0?1:0));
		}
	}
	patchFileSize+=12;

	var patchFile=new BinFile(patchFileSize);
	patchFile.fileName=fileName+'.bps';
	patchFile.littleEndian=true;
	patchFile.writeVLV=BPS_writeVLV;

	patchFile.writeString(BPS_MAGIC);
	patchFile.writeVLV(this.sourceSize);
	patchFile.writeVLV(this.targetSize);
	patchFile.writeVLV(this.metaData.length);
	patchFile.writeString(this.metaData, this.metaData.length);

	for(var i=0; i<this.actions.length; i++){
		var action=this.actions[i];
		patchFile.writeVLV(((action.length-1)<<2) + action.type);
	
		if(action.type===BPS_ACTION_TARGET_READ){
			patchFile.writeBytes(action.bytes);
		}else if(action.type===BPS_ACTION_SOURCE_COPY || action.type===BPS_ACTION_TARGET_COPY){
			patchFile.writeVLV((Math.abs(action.relativeOffset)<<1)+(action.relativeOffset<0?1:0));
		}
	}
	patchFile.writeU32(this.sourceChecksum);
	patchFile.writeU32(this.targetChecksum);
	patchFile.writeU32(this.patchChecksum);

	return patchFile;
}

function BPS_Node(){
	this.offset=0;
	this.next=null;
};
BPS_Node.prototype.delete=function(){
	if(this.next)
		delete this.next;
}
BPS.buildFromRoms=function(original, modified, deltaMode){
	var patch=new BPS();
	patch.sourceSize=original.fileSize;
	patch.targetSize=modified.fileSize;

	if(deltaMode){
		patch.actions=createBPSFromFilesDelta(original, modified);
	}else{
		patch.actions=createBPSFromFilesLinear(original, modified);
	}

	patch.sourceChecksum=original.hashCRC32();
	patch.targetChecksum=modified.hashCRC32();
	patch.patchChecksum = patch.calculateFileChecksum();
	return patch;
}


/* delta implementation from https://github.com/chiya/beat/blob/master/nall/beat/linear.hpp */
function createBPSFromFilesLinear(original, modified){
	var patchActions=[];

	/* references to match original beat code */
	var sourceData=original._u8array;
	var targetData=modified._u8array;
	var sourceSize=original.fileSize;
	var targetSize=modified.fileSize;
	var Granularity=1;



	var targetRelativeOffset=0;
	var outputOffset=0;
	var targetReadLength=0;

	function targetReadFlush(){
		if(targetReadLength){
			//encode(TargetRead | ((targetReadLength - 1) << 2));
			var action={type:BPS_ACTION_TARGET_READ, length:targetReadLength, bytes:[]};
			patchActions.push(action);
			var offset = outputOffset - targetReadLength;
			while(targetReadLength){
				//write(targetData[offset++]);
				action.bytes.push(targetData[offset++]);
				targetReadLength--;
			}
		}
	};

	while(outputOffset < targetSize) {
		var sourceLength = 0;
		for(var n = 0; outputOffset + n < Math.min(sourceSize, targetSize); n++) {
			if(sourceData[outputOffset + n] != targetData[outputOffset + n]) break;
			sourceLength++;
		}

		var rleLength = 0;
		for(var n = 1; outputOffset + n < targetSize; n++) {
			if(targetData[outputOffset] != targetData[outputOffset + n]) break;
			rleLength++;
		}

		if(rleLength >= 4) {
			//write byte to repeat
			targetReadLength++;
			outputOffset++;
			targetReadFlush();

			//copy starting from repetition byte
			//encode(TargetCopy | ((rleLength - 1) << 2));
			var relativeOffset = (outputOffset - 1) - targetRelativeOffset;
			//encode(relativeOffset << 1);
			patchActions.push({type:BPS_ACTION_TARGET_COPY, length:rleLength, relativeOffset:relativeOffset});
			outputOffset += rleLength;
			targetRelativeOffset = outputOffset - 1;
		} else if(sourceLength >= 4) {
			targetReadFlush();
			//encode(SourceRead | ((sourceLength - 1) << 2));
			patchActions.push({type:BPS_ACTION_SOURCE_READ, length:sourceLength});
			outputOffset += sourceLength;
		} else {
			targetReadLength += Granularity;
			outputOffset += Granularity;
		}
	}

	targetReadFlush();



	return patchActions;
}

/* delta implementation from https://github.com/chiya/beat/blob/master/nall/beat/delta.hpp */
function createBPSFromFilesDelta(original, modified){
	var patchActions=[];


	/* references to match original beat code */
	var sourceData=original._u8array;
	var targetData=modified._u8array;
	var sourceSize=original.fileSize;
	var targetSize=modified.fileSize;
	var Granularity=1;



	var sourceRelativeOffset=0;
	var targetRelativeOffset=0;
	var outputOffset=0;



	var sourceTree=new Array(65536);
	var targetTree=new Array(65536);
	for(var n=0; n<65536; n++){
		sourceTree[n]=null;
		targetTree[n]=null;
	}



	//source tree creation
	for(var offset=0; offset<sourceSize; offset++) {
		var symbol = sourceData[offset + 0];
		//sourceChecksum = crc32_adjust(sourceChecksum, symbol);
		if(offset < sourceSize - 1)
			symbol |= sourceData[offset + 1] << 8;
		var node=new BPS_Node();
		node.offset=offset;
		node.next=sourceTree[symbol];
		sourceTree[symbol] = node;
	}

	var targetReadLength=0;

	function targetReadFlush(){
		if(targetReadLength) {
			//encode(TargetRead | ((targetReadLength - 1) << 2));
			var action={type:BPS_ACTION_TARGET_READ, length:targetReadLength, bytes:[]};
			patchActions.push(action);
			var offset = outputOffset - targetReadLength;
			while(targetReadLength){
				//write(targetData[offset++]);
				action.bytes.push(targetData[offset++]);
				targetReadLength--;
			}
		}
	};

	while(outputOffset<modified.fileSize){
		var maxLength = 0, maxOffset = 0, mode = BPS_ACTION_TARGET_READ;
		
		var symbol = targetData[outputOffset + 0];
		if(outputOffset < targetSize - 1) symbol |= targetData[outputOffset + 1] << 8;

		{ //source read
			var length = 0, offset = outputOffset;
			while(offset < sourceSize && offset < targetSize && sourceData[offset] == targetData[offset]) {
				length++;
				offset++;
			}
			if(length > maxLength) maxLength = length, mode = BPS_ACTION_SOURCE_READ;
		}

		{ //source copy
			var node = sourceTree[symbol];
			while(node) {
				var length = 0, x = node.offset, y = outputOffset;
				while(x < sourceSize && y < targetSize && sourceData[x++] == targetData[y++]) length++;
				if(length > maxLength) maxLength = length, maxOffset = node.offset, mode = BPS_ACTION_SOURCE_COPY;
				node = node.next;
			}
		}

		{ //target copy
			var node = targetTree[symbol];
			while(node) {
				var length = 0, x = node.offset, y = outputOffset;
				while(y < targetSize && targetData[x++] == targetData[y++]) length++;
				if(length > maxLength) maxLength = length, maxOffset = node.offset, mode = BPS_ACTION_TARGET_COPY;
				node = node.next;
			}

			//target tree append
			node = new BPS_Node();
			node.offset = outputOffset;
			node.next = targetTree[symbol];
			targetTree[symbol] = node;
		}

		{ //target read
			if(maxLength < 4) {
				maxLength = Math.min(Granularity, targetSize - outputOffset);
				mode = BPS_ACTION_TARGET_READ;
			}
		}

		if(mode != BPS_ACTION_TARGET_READ) targetReadFlush();

		switch(mode) {
			case BPS_ACTION_SOURCE_READ:
				//encode(BPS_ACTION_SOURCE_READ | ((maxLength - 1) << 2));
				patchActions.push({type:BPS_ACTION_SOURCE_READ, length:maxLength});
				break;
			case BPS_ACTION_TARGET_READ:
				//delay write to group sequential TargetRead commands into one
				targetReadLength += maxLength;
				break;
			case BPS_ACTION_SOURCE_COPY:
			case BPS_ACTION_TARGET_COPY:
				//encode(mode | ((maxLength - 1) << 2));
				var relativeOffset;
				if(mode == BPS_ACTION_SOURCE_COPY) {
					relativeOffset = maxOffset - sourceRelativeOffset;
					sourceRelativeOffset = maxOffset + maxLength;
				} else {
					relativeOffset = maxOffset - targetRelativeOffset;
					targetRelativeOffset = maxOffset + maxLength;
				}
				//encode((relativeOffset < 0) | (abs(relativeOffset) << 1));
				patchActions.push({type:mode, length:maxLength, relativeOffset:relativeOffset});
				break;
		}

		outputOffset += maxLength;
	}
	
	targetReadFlush();


	return patchActions;
}
/* ---- modules/RomPatcher.format.rup.js ---- */
/* RUP module for Rom Patcher JS v20250430 - Marc Robledo 2018-2025 - http://www.marcrobledo.com/license */
/* File format specification: http://www.romhacking.net/documents/288/ */

const RUP_MAGIC='NINJA2';
const RUP_COMMAND_END=0x00;
const RUP_COMMAND_OPEN_NEW_FILE=0x01;
const RUP_COMMAND_XOR_RECORD=0x02;
const RUP_ROM_TYPES=['raw','nes','fds','snes','n64','gb','sms','mega','pce','lynx'];
if(typeof module !== "undefined" && module.exports){
	module.exports = RUP;
}

function RUP(){
	this.author='';
	this.version='';
	this.title='';
	this.genre='';
	this.language='';
	this.date='';
	this.web='';
	this.description='';
	this.files=[];
}
RUP.prototype.toString=function(){
	var s='Author: '+this.author;
	s+='\nVersion: '+this.version;
	s+='\nTitle: '+this.title;
	s+='\nGenre: '+this.genre;
	s+='\nLanguage: '+this.language;
	s+='\nDate: '+this.date;
	s+='\nWeb: '+this.web;
	s+='\nDescription: '+this.description;
	for(var i=0; i<this.files.length; i++){
		var file=this.files[i];
		s+='\n---------------';
		s+='\nFile '+i+':';
		s+='\nFile name: '+file.fileName;
		s+='\nRom type: '+RUP_ROM_TYPES[file.romType];
		s+='\nSource file size: '+file.sourceFileSize;
		s+='\nTarget file size: '+file.targetFileSize;
		s+='\nSource MD5: '+file.sourceMD5;
		s+='\nTarget MD5: '+file.targetMD5;
		if(file.overflowMode==='A'){
			s+='\nOverflow mode: Append ' + file.overflowData.length + ' bytes';
		}else if(file.overflowMode==='M'){
			s+='\nOverflow mode: Minify ' + file.overflowData.length + ' bytes';
		}
		s+='\n#records: '+file.records.length;
	}
	return s
}


RUP.prototype.validateSource=function(romFile,headerSize){
	var md5string=romFile.hashMD5(headerSize);
	for(var i=0; i<this.files.length; i++){
		if(this.files[i].sourceMD5===md5string || this.files[i].targetMD5===md5string){
			return {
				file:this.files[i],
				undo:this.files[i].targetMD5===md5string
			};
		}
	}
	return false;
}
RUP.prototype.getValidationInfo=function(){
	var values=[];
	for(var i=0; i<this.files.length; i++){
		values.push(this.files[i].sourceMD5);
	}
	return {
			'type':'MD5',
			'value':values
		};
}
RUP.prototype.getDescription=function(){
	return this.description? this.description : null;
}
RUP.prototype.apply=function(romFile, validate){
	var validFile;
	if(validate){
		validFile=this.validateSource(romFile);
		
		if(!validFile)
			throw new Error('Source ROM checksum mismatch');
	}else{
		validFile={
			file:this.files[0],
			undo:this.files[0].targetMD5===romFile.hashMD5()
		};
	}

	var undo=validFile.undo;
	var patch=validFile.file;

	tempFile=new BinFile(!undo? patch.targetFileSize : patch.sourceFileSize);
	/* copy original file */
	romFile.copyTo(tempFile, 0);


	for(var i=0; i<patch.records.length; i++){
		var offset=patch.records[i].offset;
		romFile.seek(offset);
		tempFile.seek(offset);
		for(var j=0; j<patch.records[i].xor.length; j++){
			tempFile.writeU8(
				(romFile.isEOF()?0x00:romFile.readU8()) ^ patch.records[i].xor[j]
			);
		}
	}

	/* add overflow data if needed */
	if(patch.overflowMode==='A' && !undo){ /* append */
		tempFile.seek(patch.sourceFileSize);
		tempFile.writeBytes(patch.overflowData.map((byte) => byte ^ 0xff));
	}else if(patch.overflowMode==='M' && undo){ /* minify */
		tempFile.seek(patch.targetFileSize);
		tempFile.writeBytes(patch.overflowData.map((byte) => byte ^ 0xff));
	}


	if(
		validate && 
		(
			(!undo && tempFile.hashMD5()!==patch.targetMD5) ||
			(undo && tempFile.hashMD5()!==patch.sourceMD5)
		)
	){
		throw new Error('Target ROM checksum mismatch');
	}

	if(undo)
		tempFile.unpatched=true;

	return tempFile
}

RUP.MAGIC=RUP_MAGIC;

RUP.padZeroes=function(intVal, nBytes){
	var hexString=intVal.toString(16);
	while(hexString.length<nBytes*2)
		hexString='0'+hexString;
	return hexString
};
RUP.fromFile=function(file){
	var patch=new RUP();
	file.readVLV=RUP_readVLV;

	file.seek(RUP_MAGIC.length);

	patch.textEncoding=file.readU8();
	patch.author=file.readString(84);
	patch.version=file.readString(11);
	patch.title=file.readString(256);
	patch.genre=file.readString(48);
	patch.language=file.readString(48);
	patch.date=file.readString(8);
	patch.web=file.readString(512);
	patch.description=file.readString(1074).replace(/\\n/g,'\n');


	file.seek(0x800);
	var nextFile;
	while(!file.isEOF()){
		var command=file.readU8();

		if(command===RUP_COMMAND_OPEN_NEW_FILE){
			if(nextFile)
				patch.files.push(nextFile)

			nextFile={
				records:[]
			};


			nextFile.fileName=file.readString(file.readVLV());
			nextFile.romType=file.readU8();
			nextFile.sourceFileSize=file.readVLV();
			nextFile.targetFileSize=file.readVLV();

			nextFile.sourceMD5='';
			for(var i=0; i<16; i++)
				nextFile.sourceMD5+=RUP.padZeroes(file.readU8(),1);

			nextFile.targetMD5='';
			for(var i=0; i<16; i++)
				nextFile.targetMD5+=RUP.padZeroes(file.readU8(),1);



			if(nextFile.sourceFileSize!==nextFile.targetFileSize){
				nextFile.overflowMode=file.readString(1); // 'M' (source>target) or 'A' (source<target)
				if(nextFile.overflowMode!=='M' && nextFile.overflowMode!=='A')
					throw new Error('RUP: invalid overflow mode');
				nextFile.overflowData=file.readBytes(file.readVLV());
			}

		}else if(command===RUP_COMMAND_XOR_RECORD){
			nextFile.records.push({
				offset:file.readVLV(),
				xor:file.readBytes(file.readVLV())
			});
		}else if(command===RUP_COMMAND_END){
			if(nextFile)
				patch.files.push(nextFile);
			break;
		}else{
			throw new Error('invalid RUP command');
		}
	}
	return patch;
}


function RUP_readVLV(){
	var nBytes=this.readU8();
	var data=0;
	for(var i=0; i<nBytes; i++){
		data+=this.readU8() << i*8;
	}
	return data;
}
function RUP_writeVLV(data){
	var len=RUP_getVLVLen(data)-1;

	this.writeU8(len);

	while(data){
		this.writeU8(data & 0xff);
		data>>=8;
	}
}
function RUP_getVLVLen(data){
	var ret=1;
	while(data){
		ret++;
		data>>=8;
	}
	return ret;
}






RUP.prototype.export=function(fileName){
	var patchFileSize=2048;	
	for(var i=0; i<this.files.length; i++){
		var file=this.files[i];
		patchFileSize++; //command 0x01

		patchFileSize+=RUP_getVLVLen(file.fileName.length);
		patchFileSize+=file.fileName.length;
		patchFileSize++; //rom type
		patchFileSize+=RUP_getVLVLen(file.sourceFileSize);
		patchFileSize+=RUP_getVLVLen(file.targetFileSize);
		patchFileSize+=32; //MD5s

		if(file.sourceFileSize!==file.targetFileSize){
			patchFileSize++; // M or A
			patchFileSize+=RUP_getVLVLen(file.overflowData.length);
			patchFileSize+=file.overflowData.length;
		}
		for(var j=0; j<file.records.length; j++){
			patchFileSize++; //command 0x01
			patchFileSize+=RUP_getVLVLen(file.records[j].offset);
			patchFileSize+=RUP_getVLVLen(file.records[j].xor.length);
			patchFileSize+=file.records[j].xor.length;
		}
	}
	patchFileSize++; //command 0x00


	var patchFile=new BinFile(patchFileSize);
	patchFile.fileName=fileName+'.rup';
	patchFile.writeVLV=RUP_writeVLV;





	patchFile.writeString(RUP_MAGIC);
	patchFile.writeU8(this.textEncoding);
	patchFile.writeString(this.author, 84);
	patchFile.writeString(this.version, 11);
	patchFile.writeString(this.title, 256);
	patchFile.writeString(this.genre, 48);
	patchFile.writeString(this.language, 48);
	patchFile.writeString(this.date, 8);
	patchFile.writeString(this.web, 512);
	patchFile.writeString(this.description.replace(/\n/g,'\\n'), 1074);

	for(var i=0; i<this.files.length; i++){
		var file=this.files[i];
		patchFile.writeU8(RUP_COMMAND_OPEN_NEW_FILE);

		patchFile.writeVLV(file.fileName);
		patchFile.writeU8(file.romType);
		patchFile.writeVLV(file.sourceFileSize);
		patchFile.writeVLV(file.targetFileSize);

		for(var j=0; j<16; j++)
			patchFile.writeU8(parseInt(file.sourceMD5.substr(j*2,2), 16));
		for(var j=0; j<16; j++)
			patchFile.writeU8(parseInt(file.targetMD5.substr(j*2,2), 16));



		if(file.sourceFileSize!==file.targetFileSize){
			patchFile.writeString(file.sourceFileSize>file.targetFileSize?'M':'A');
			patchFile.writeVLV(file.overflowData.length);
			patchFile.writeBytes(file.overflowData);
		}

		for(var j=0; j<file.records.length; j++){
			patchFile.writeU8(RUP_COMMAND_XOR_RECORD);

			patchFile.writeVLV(file.records[j].offset);
			patchFile.writeVLV(file.records[j].xor.length);
			patchFile.writeBytes(file.records[j].xor);
		}
	}

	patchFile.writeU8(RUP_COMMAND_END);



	return patchFile;
}



RUP.buildFromRoms=function(original, modified, description){
	var patch=new RUP();

	var today=new Date();
	patch.date=(today.getYear()+1900)+RUP.padZeroes(today.getMonth()+1, 1)+RUP.padZeroes(today.getDate(), 1);
	if(description)
		patch.description=description;

	var file={
		fileName:'',
		romType:0,
		sourceFileSize:original.fileSize,
		targetFileSize:modified.fileSize,
		sourceMD5:original.hashMD5(),
		targetMD5:modified.hashMD5(),
		overflowMode:null,
		overflowData:[],
		records:[]
	};

	if(file.sourceFileSize<file.targetFileSize){
		modified.seek(file.sourceFileSize);
		file.overflowMode='A';
		file.overflowData=modified.readBytes(file.targetFileSize-file.sourceFileSize).map((byte) => byte ^ 0xff);
		modified=modified.slice(0, file.sourceFileSize);
	}else if(file.sourceFileSize>file.targetFileSize){
		original.seek(file.targetFileSize);
		file.overflowMode='M';
		file.overflowData=original.readBytes(file.sourceFileSize-file.targetFileSize).map((byte) => byte ^ 0xff);
		original=original.slice(0, file.targetFileSize);
	}


	original.seek(0);
	modified.seek(0);

	while(!modified.isEOF()){
		var b1=original.isEOF()?0x00:original.readU8();
		var b2=modified.readU8();

		if(b1!==b2){
			var originalOffset=modified.offset-1;
			var xorDifferences=[];

			while(b1!==b2){
				xorDifferences.push(b1^b2);

				if(modified.isEOF())
					break;

				b1=original.isEOF()?0x00:original.readU8();
				b2=modified.readU8();
			}

			file.records.push({offset:originalOffset, xor:xorDifferences});
		}
	}

	patch.files.push(file);

	return patch
}
/* ---- modules/RomPatcher.format.ppf.js ---- */
/* PPF module for Rom Patcher JS v20200221 - Marc Robledo 2019-2020 - http://www.marcrobledo.com/license */
/* File format specification: https://www.romhacking.net/utilities/353/  */


const PPF_MAGIC='PPF';
const PPF_IMAGETYPE_BIN=0x00;
const PPF_IMAGETYPE_GI=0x01;
const PPF_BEGIN_FILE_ID_DIZ_MAGIC='@BEG';//@BEGIN_FILE_ID.DIZ

if(typeof module !== "undefined" && module.exports){
	module.exports = PPF;
}
function PPF(){
	this.version=3;
	this.imageType=PPF_IMAGETYPE_BIN;
	this.blockCheck=false;
	this.undoData=false;
	this.records=[];
}
PPF.prototype.addRecord=function(offset, data, undoData){
	if(this.undoData){
		this.records.push({offset:offset, data:data, undoData:undoData});
	}else{
		this.records.push({offset:offset, data:data});
	}
}
PPF.prototype.toString=function(){
	var s=this.description;
	s+='\nPPF version: '+this.version;
	s+='\n#Records: '+this.records.length;
	s+='\nImage type: '+this.imageType;
	s+='\nBlock check: '+!!this.blockCheck;
	s+='\nUndo data: '+this.undoData;
	if(this.fileIdDiz)
		s+='\nFILE_ID.DIZ: '+this.fileIdDiz;
	return s
}
PPF.prototype.export=function(fileName){
	var patchFileSize=5+1+50; //PPFx0
	for(var i=0; i<this.records.length; i++){
		patchFileSize+=4+1+this.records[i].data.length;
		if(this.version===3)
			patchFileSize+=4; //offsets are u64
	}

	if(this.version===3 || this.version===2){
		patchFileSize+=4;
	}
	if(this.blockCheck){
		patchFileSize+=1024;
	}
	if(this.fileIdDiz){
		patchFileSize+=18+this.fileIdDiz.length+16+4;
	}

	tempFile=new BinFile(patchFileSize);
	tempFile.fileName=fileName+'.ppf';
	tempFile.writeString(PPF_MAGIC);
	tempFile.writeString((this.version*10).toString());
	tempFile.writeU8(parseInt(this.version)-1);
	tempFile.writeString(this.description, 50);


	if(this.version===3){
		tempFile.writeU8(this.imageType);
		tempFile.writeU8(this.blockCheck?0x01:0x00);
		tempFile.writeU8(this.undoData?0x01:0x00);
		tempFile.writeU8(0x00); //dummy

	}else if(this.version===2){
		tempFile.writeU32(this.inputFileSize);
	}

	if(this.blockCheck){
		tempFile.writeBytes(this.blockCheck);
	}



	tempFile.littleEndian=true;
	for(var i=0; i<this.records.length; i++){
		tempFile.writeU32(this.records[i].offset & 0xffffffff);

		if(this.version===3){
			var offset2=this.records[i].offset;
			for(var j=0; j<32; j++)
				offset2=parseInt((offset2/2)>>>0);
			tempFile.writeU32(offset2);
		}
		tempFile.writeU8(this.records[i].data.length);
		tempFile.writeBytes(this.records[i].data);
		if(this.undoData)
			tempFile.writeBytes(this.records[i].undoData);
	}

	if(this.fileIdDiz){
		tempFile.writeString('@BEGIN_FILE_ID.DIZ');
		tempFile.writeString(this.fileIdDiz);
		tempFile.writeString('@END_FILE_ID.DIZ');
		tempFile.writeU16(this.fileIdDiz.length);
		tempFile.writeU16(0x00);
	}



	return tempFile
}
PPF.prototype.apply=function(romFile){
	var newFileSize=romFile.fileSize;
	for(var i=0; i<this.records.length; i++){
		if(this.records[i].offset+this.records[i].data.length>newFileSize)
			newFileSize=this.records[i].offset+this.records[i].data.length;
	}
	if(newFileSize===romFile.fileSize){
		tempFile=romFile.slice(0, romFile.fileSize);
	}else{
		tempFile=new BinFile(newFileSize);
		romFile.copyTo(tempFile,0);
	}

	//check if undoing
	var undoingData=false;
	if(this.undoData){
		tempFile.seek(this.records[0].offset);
		var originalBytes=tempFile.readBytes(this.records[0].data.length);
		var foundDifferences=false;
		for(var i=0; i<originalBytes.length && !foundDifferences; i++){
			if(originalBytes[i]!==this.records[0].data[i]){
				foundDifferences=true;
			}
		}
		if(!foundDifferences){
			undoingData=true;
		}
	}

	for(var i=0; i<this.records.length; i++){
		tempFile.seek(this.records[i].offset);

		if(undoingData){
			tempFile.writeBytes(this.records[i].undoData);
		}else{
			tempFile.writeBytes(this.records[i].data);
		}
	}

	return tempFile
}

PPF.MAGIC=PPF_MAGIC;



PPF.fromFile=function(patchFile){
	var patch=new PPF();

	
	patchFile.seek(3);
	var version1=parseInt(patchFile.readString(2))/10;
	var version2=patchFile.readU8()+1;
	if(version1!==version2 || version1>3){
		throw new Error('invalid PPF version');
	}

	patch.version=version1;
	patch.description=patchFile.readString(50).replace(/ +$/,'');




	if(patch.version===3){
		patch.imageType=patchFile.readU8();
		if(patchFile.readU8())
			patch.blockCheck=true;
		if(patchFile.readU8())
			patch.undoData=true;

		patchFile.skip(1);
	}else if(patch.version===2){
		patch.blockCheck=true;
		patch.inputFileSize=patchFile.readU32();
	}

	if(patch.blockCheck){
		patch.blockCheck=patchFile.readBytes(1024);
	}



	patchFile.littleEndian=true;
	while(!patchFile.isEOF()){

		if(patchFile.readString(4)===PPF_BEGIN_FILE_ID_DIZ_MAGIC){
			patchFile.skip(14);
			//console.log('found file_id.diz begin');
			patch.fileIdDiz=patchFile.readString(3072);
			patch.fileIdDiz=patch.fileIdDiz.substr(0, patch.fileIdDiz.indexOf('@END_FILE_ID.DIZ'));
			break;
		}
		patchFile.skip(-4);

		var offset;
		if(patch.version===3){
			var u64_1=patchFile.readU32();
			var u64_2=patchFile.readU32();
			offset=u64_1+(u64_2*0x100000000);
		}else
			offset=patchFile.readU32();

		var len=patchFile.readU8();
		var data=patchFile.readBytes(len);

		var undoData=false;
		if(patch.undoData){
			undoData=patchFile.readBytes(len);
		}

		patch.addRecord(offset, data, undoData);
	}



	return patch;
}


PPF.buildFromRoms=function(original, modified){
	var patch=new PPF();

	patch.description='Patch description';

	if(original.fileSize>modified.fileSize){
		var expandedModified=new BinFile(original.fileSize);
		modified.copyTo(expandedModified,0);
		modified=expandedModified;
	}

	original.seek(0);
	modified.seek(0);
	while(!modified.isEOF()){
		var b1=original.isEOF()?0x00:original.readU8();
		var b2=modified.readU8();

		if(b1!==b2){
			var differentData=[];
			var offset=modified.offset-1;

			while(b1!==b2 && differentData.length<0xff){
				differentData.push(b2);

				if(modified.isEOF() || differentData.length===0xff)
					break;

				b1=original.isEOF()?0x00:original.readU8();
				b2=modified.readU8();
			}

			patch.addRecord(offset, differentData);
		}
	}
	
	if(original.fileSize<modified.fileSize){
		modified.seek(modified.fileSize-1);
		if(modified.readU8()===0x00)
			patch.addRecord(modified.fileSize-1, [0x00]);
	}

	return patch
}
/* ---- modules/RomPatcher.format.bdf.js ---- */
/* BDF module for Rom Patcher JS v20250922 - Marc Robledo 2025 - http://www.marcrobledo.com/license */
/* File format specification: https://www.daemonology.net/bsdiff/ */

const BDF_MAGIC='BSDIFF40';

if(typeof module !== "undefined" && module.exports){
	module.exports = BDF;
}

function BDF(){
    this.records=[];
    this.patchedSize=0;
}

BDF.prototype.apply=function(file){
    var tempFile=new BinFile(this.patchedSize);
    
    for (const record of this.records) {
        for (const b of record.diff) {
            tempFile.writeU8(file.readU8() + b);
        }
        tempFile.writeBytes(record.extra);
        file.seek(file.offset + record.skip);
    }
    return tempFile;
}

BDF.MAGIC = BDF_MAGIC;

BDF.fromFile=function(file){
    var patch=new BDF();

    file.seek(8);
    file.littleEndian=true;
    var controlSize=file.readU64();
    var diffSize=file.readU64();
    patch.patchedSize=file.readU64();

    var controlCompressed=file.readBytes(controlSize);
    var diffCompressed=file.readBytes(diffSize);
    var extraCompressed=file.readBytes(file.fileSize-file.offset);

    var controlFile=new BinFile(bz2.decompress(controlCompressed));
    controlFile.littleEndian=true;
    var diffFile=new BinFile(bz2.decompress(diffCompressed));
    var extraFile=new BinFile(bz2.decompress(extraCompressed));

    while(!controlFile.isEOF()){
        var diffLen=controlFile.readU64();
        var extraLen=controlFile.readU64();
        var skip=controlFile.readU64();
        if(skip&(1<<63))
            skip=-(skip&~(1<<63));
        var diff=diffFile.readBytes(diffLen);
        var extra=extraFile.readBytes(extraLen);
        patch.records.push({diff, extra, skip});
    }

    return patch;
}




/*
bz2 (C) 2019-present SheetJS LLC

Copyright 2019 SheetJS LLC

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

*/
"use strict";
const bz2=function $(){let x=[0,79764919,159529838,222504665,319059676,398814059,445009330,507990021,638119352,583659535,797628118,726387553,890018660,835552979,1015980042,944750013,1276238704,1221641927,1167319070,1095957929,1595256236,1540665371,1452775106,1381403509,1780037320,1859660671,1671105958,1733955601,2031960084,2111593891,1889500026,1952343757,2552477408,2632100695,2443283854,2506133561,2334638140,2414271883,2191915858,2254759653,3190512472,3135915759,3081330742,3009969537,2905550212,2850959411,2762807018,2691435357,3560074640,3505614887,3719321342,3648080713,3342211916,3287746299,3467911202,3396681109,4063920168,4143685023,4223187782,4286162673,3779000052,3858754371,3904687514,3967668269,881225847,809987520,1023691545,969234094,662832811,591600412,771767749,717299826,311336399,374308984,453813921,533576470,25881363,88864420,134795389,214552010,2023205639,2086057648,1897238633,1976864222,1804852699,1867694188,1645340341,1724971778,1587496639,1516133128,1461550545,1406951526,1302016099,1230646740,1142491917,1087903418,2896545431,2825181984,2770861561,2716262478,3215044683,3143675388,3055782693,3001194130,2326604591,2389456536,2200899649,2280525302,2578013683,2640855108,2418763421,2498394922,3769900519,3832873040,3912640137,3992402750,4088425275,4151408268,4197601365,4277358050,3334271071,3263032808,3476998961,3422541446,3585640067,3514407732,3694837229,3640369242,1762451694,1842216281,1619975040,1682949687,2047383090,2127137669,1938468188,2001449195,1325665622,1271206113,1183200824,1111960463,1543535498,1489069629,1434599652,1363369299,622672798,568075817,748617968,677256519,907627842,853037301,1067152940,995781531,51762726,131386257,177728840,240578815,269590778,349224269,429104020,491947555,4046411278,4126034873,4172115296,4234965207,3794477266,3874110821,3953728444,4016571915,3609705398,3555108353,3735388376,3664026991,3290680682,3236090077,3449943556,3378572211,3174993278,3120533705,3032266256,2961025959,2923101090,2868635157,2813903052,2742672763,2604032198,2683796849,2461293480,2524268063,2284983834,2364738477,2175806836,2238787779,1569362073,1498123566,1409854455,1355396672,1317987909,1246755826,1192025387,1137557660,2072149281,2135122070,1912620623,1992383480,1753615357,1816598090,1627664531,1707420964,295390185,358241886,404320391,483945776,43990325,106832002,186451547,266083308,932423249,861060070,1041341759,986742920,613929101,542559546,756411363,701822548,3316196985,3244833742,3425377559,3370778784,3601682597,3530312978,3744426955,3689838204,3819031489,3881883254,3928223919,4007849240,4037393693,4100235434,4180117107,4259748804,2310601993,2373574846,2151335527,2231098320,2596047829,2659030626,2470359227,2550115596,2947551409,2876312838,2788305887,2733848168,3165939309,3094707162,3040238851,2985771188,],f=[0,1,3,7,15,31,63,127,255,511,1023,2047,4095,8191,16383,32767,65535,131071,262143,524287,1048575,2097151,4194303,8388607,16777215,33554431,67108863,134217727,268435455,536870911,1073741823,-2147483648,];function e($){let x=[];for(let f=0;f<$.length;f+=1)x.push([f,$[f]]);x.push([$.length,-1]);let e=[],d=x[0][0],b=x[0][1];for(let _=0;_<x.length;_+=1){let a=x[_][0],c=x[_][1];if(b)for(let t=d;t<a;t+=1)e.push({code:t,bits:b,symbol:void 0});if(d=a,b=c,-1===c)break}e.sort(($,x)=>$.bits-x.bits||$.code-x.code);let l=0,o=-1,r=[],n;for(let s=0;s<e.length;s+=1){let i=e[s];o+=1,i.bits!==l&&(o<<=i.bits-l,n=r[l=i.bits]={}),i.symbol=o,n[o]=i}return{table:e,fastAccess:r}}function d($,x){if(x<0||x>=$.length)throw RangeError("Out of bound");let f=$.slice();$.sort(($,x)=>$-x);let e={};for(let d=$.length-1;d>=0;d-=1)e[$[d]]=d;let b=[];for(let _=0;_<$.length;_+=1)b.push(e[f[_]]++);let a,c=$[a=x],t=[];for(let l=1;l<$.length;l+=1){let o=$[a=b[a]];void 0===o?t.push(255):t.push(o)}return t.push(c),t.reverse(),t}let b={decompress:function $(b,_=!1){let a=0,c=0,t=0,l=$=>{if($>=32){let x=$>>1;return l(x)*(1<<x)+l($-x)}for(;t<$;)c=(c<<8)+b[a],a+=1,t+=8;let e=f[$],d=c>>t-$&e;return t-=$,c&=~(e<<t),d},o=l(16);if(16986!==o)throw Error("Invalid magic");let r=l(8);if(104!==r)throw Error("Invalid method");let n=l(8);if(n>=49&&n<=57)n-=48;else throw Error("Invalid blocksize");let s=new Uint8Array(1.5*b.length),i=0,h=-1;for(;;){let u=l(48),p=0|l(32);if(54156738319193===u){if(l(1))throw Error("do not support randomised");let g=l(24),w=[],m=l(16);for(let v=32768;v>0;v>>=1){if(!(m&v)){for(let y=0;y<16;y+=1)w.push(!1);continue}let k=l(16);for(let I=32768;I>0;I>>=1)w.push(!!(k&I))}let A=l(3);if(A<2||A>6)throw Error("Invalid number of huffman groups");let z=l(15),C=[],O=Array.from({length:A},($,x)=>x);for(let F=0;F<z;F+=1){let H=0;for(;l(1);)if((H+=1)>=A)throw Error("MTF table out of range");let M=O[H];for(let P=H;P>0;O[P]=O[--P]);C.push(M),O[0]=M}let R=w.reduce(($,x)=>$+x,0)+2,T=[];for(let j=0;j<A;j+=1){let q=l(5),B=[];for(let D=0;D<R;D+=1){if(q<0||q>20)throw Error("Huffman group length outside range");for(;l(1);)q-=2*l(1)-1;B.push(q)}T.push(e(B))}let E=[];for(let G=0;G<w.length-1;G+=1)w[G]&&E.push(G);let J=0,K=0,L,N,Q=0,S=0,U=[];for(;;){for(let V in(J-=1)<=0&&(J=50,K<=C.length&&(L=T[C[K]],K+=1)),L.fastAccess)if(Object.prototype.hasOwnProperty.call(L.fastAccess,V)&&(t<V&&(c=(c<<8)+b[a],a+=1,t+=8),N=L.fastAccess[V][c>>t-V])){c&=f[t-=V],N=N.code;break}if(N>=0&&N<=1){0===Q&&(S=1),Q+=S<<N,S<<=1;continue}{let W=E[0];for(;Q>0;Q-=1)U.push(W)}if(N===R-1)break;{let X=E[N-1];for(let Y=N-1;Y>0;E[Y]=E[--Y]);E[0]=X,U.push(X)}}let Z=d(U,g),$$=0;for(;$$<Z.length;){let $x=Z[$$],$f=1;if($$<Z.length-4&&Z[$$+1]===$x&&Z[$$+2]===$x&&Z[$$+3]===$x?($f=Z[$$+4]+4,$$+=5):$$+=1,i+$f>=s.length){let $e=s;(s=new Uint8Array(2*$e.length)).set($e)}for(let $d=0;$d<$f;$d+=1)_&&(h=h<<8^x[(h>>24^$x)&255]),s[i]=$x,i+=1}if(_){let $b=-1^h;if($b!==p)throw Error(`CRC mismatch: ${$b} !== ${p}`);h=-1}}else if(25779555029136===u){l(7&t);break}else throw Error("Invalid bz2 blocktype")}return s.subarray(0,i)}};return b}();
/* ---- modules/RomPatcher.format.pmsr.js ---- */
/* PMSR (Paper Mario Star Rod) module for Rom Patcher JS v20240721 - Marc Robledo 2020-2024 - http://www.marcrobledo.com/license */
/* File format specification: http://origami64.net/attachment.php?aid=790 (dead link) */

const PMSR_MAGIC='PMSR';
const YAY0_MAGIC='Yay0';
const PAPER_MARIO_USA10_CRC32=0xa7f5cd7e;
const PAPER_MARIO_USA10_FILE_SIZE=41943040;
if(typeof module !== "undefined" && module.exports){
	module.exports = PMSR;
}

function PMSR(){
	this.targetSize=0;
	this.records=[];
}
PMSR.prototype.addRecord=function(offset, data){
	this.records.push({offset:offset, data:data})
}
PMSR.prototype.toString=function(){
	var s='Star Rod patch';
	s+='\nTarget file size: '+this.targetSize;
	s+='\n#Records: '+this.records.length;
	return s;
}


PMSR.prototype.validateSource=function(romFile){
	return romFile.fileSize===PAPER_MARIO_USA10_FILE_SIZE && romFile.hashCRC32()===PAPER_MARIO_USA10_CRC32;
}
PMSR.prototype.getValidationInfo=function(){
	return {
		'type':'CRC32',
		'value':PAPER_MARIO_USA10_CRC32
	}
}
PMSR.prototype.apply=function(romFile, validate){
	if(validate && !this.validateSource(romFile)){
		throw new Error('Source ROM checksum mismatch');
	}

	console.log('a');
	if(this.targetSize===romFile.fileSize){
		tempFile=romFile.slice(0, romFile.fileSize);
	}else{
		tempFile=new BinFile(this.targetSize);
		romFile.copyTo(tempFile,0);
	}

	console.log('b');
	for(var i=0; i<this.records.length; i++){
		tempFile.seek(this.records[i].offset);
		tempFile.writeBytes(this.records[i].data);
	}
	
	return tempFile;
}

PMSR.MAGIC=PMSR_MAGIC;

PMSR.fromFile=function(file){
	var patch=new PMSR();

	/*file.seek(0);
	if(file.readString(YAY0_MAGIC.length)===YAY0_MAGIC){
		file=PMSR.YAY0_decode(file);
	}*/

	patch.targetSize=PAPER_MARIO_USA10_FILE_SIZE;

	file.seek(4);
	var nRecords=file.readU32();

	for(var i=0; i<nRecords; i++){
		var offset=file.readU32();
		var length=file.readU32();
		patch.addRecord(offset, file.readBytes(length));

		if((offset+length)>patch.targetSize)
			patch.targetSize=offset+length;
	}

	return patch;
}





/* to-do */
//PMSR.prototype.export=function(fileName){return null}
//PMSR.buildFromRoms=function(original, modified){return null}


/* https://github.com/pho/WindViewer/wiki/Yaz0-and-Yay0 */
PMSR.YAY0_decode=function(file){
	/* to-do */
}
/* ---- modules/RomPatcher.format.vcdiff.js ---- */
/* VCDIFF module for RomPatcher.js v20181021 - Marc Robledo 2018 - http://www.marcrobledo.com/license */
/* File format specification: https://tools.ietf.org/html/rfc3284 */
/*
	Mostly based in:
	https://github.com/vic-alexiev/TelerikAcademy/tree/master/C%23%20Fundamentals%20II/Homework%20Assignments/3.%20Methods/000.%20MiscUtil/Compression/Vcdiff
	some code and ideas borrowed from:
	https://hack64.net/jscripts/libpatch.js?6
*/
//const VCDIFF_MAGIC=0xd6c3c400;
const VCDIFF_MAGIC='\xd6\xc3\xc4';
/*
const XDELTA_014_MAGIC='%XDELTA';
const XDELTA_018_MAGIC='%XDZ000';
const XDELTA_020_MAGIC='%XDZ001';
const XDELTA_100_MAGIC='%XDZ002';
const XDELTA_104_MAGIC='%XDZ003';
const XDELTA_110_MAGIC='%XDZ004';
*/
if(typeof module !== "undefined" && module.exports){
	module.exports = VCDIFF;
	BinFile = require("./BinFile");
}

function VCDIFF(patchFile){
	this.file=patchFile;
}
VCDIFF.prototype.toString=function(){
	return 'VCDIFF patch'
}

VCDIFF.prototype.apply=function(romFile, validate){
	//romFile._u8array=new Uint8Array(romFile._dataView.buffer);

	//var t0=performance.now();
	var parser=new VCDIFF_Parser(this.file);

	//read header
	parser.seek(4);
	var headerIndicator=parser.readU8();

	if(headerIndicator & VCD_DECOMPRESS){
		//has secondary decompressor, read its id
		var secondaryDecompressorId=parser.readU8();

		if(secondaryDecompressorId!==0)
			throw new Error('not implemented: secondary decompressor');
	}


	if(headerIndicator & VCD_CODETABLE){
		var codeTableDataLength=parser.read7BitEncodedInt();

		if(codeTableDataLength!==0)
			throw new Error('not implemented: custom code table'); // custom code table
	}

	if(headerIndicator & VCD_APPHEADER){
		// ignore app header data
		var appDataLength=parser.read7BitEncodedInt();
		parser.skip(appDataLength);
	}
	var headerEndOffset=parser.offset;

	//calculate target file size
	var newFileSize=0;
	while(!parser.isEOF()){
		var winHeader=parser.decodeWindowHeader();
		newFileSize+=winHeader.targetWindowLength;
		parser.skip(winHeader.addRunDataLength + winHeader.addressesLength + winHeader.instructionsLength);
	}
	tempFile=new BinFile(newFileSize);




	parser.seek(headerEndOffset);



	var cache = new VCD_AdressCache(4,3);
	var codeTable = VCD_DEFAULT_CODE_TABLE;

	var targetWindowPosition = 0; //renombrar

	while(!parser.isEOF()){
		var winHeader = parser.decodeWindowHeader();

		var addRunDataStream = new VCDIFF_Parser(this.file, parser.offset);
		var instructionsStream = new VCDIFF_Parser(this.file, addRunDataStream.offset + winHeader.addRunDataLength);
		var addressesStream = new VCDIFF_Parser(this.file, instructionsStream.offset + winHeader.instructionsLength);

		var addRunDataIndex = 0;

		cache.reset(addressesStream);

		var addressesStreamEndOffset = addressesStream.offset;
		while(instructionsStream.offset<addressesStreamEndOffset){
			/*
			var instructionIndex=instructionsStream.readS8();
			if(instructionIndex===-1){
				break;
			}
			*/
			var instructionIndex = instructionsStream.readU8();


			for(var i=0; i<2; i++){
				var instruction=codeTable[instructionIndex][i];
				var size=instruction.size;

				if(size===0 && instruction.type!==VCD_NOOP){
					size=instructionsStream.read7BitEncodedInt()
				}

				if(instruction.type===VCD_NOOP){
					continue;

				}else if(instruction.type===VCD_ADD){
					addRunDataStream.copyToFile2(tempFile, addRunDataIndex+targetWindowPosition, size);
					addRunDataIndex += size;

				}else if(instruction.type===VCD_COPY){
					var addr = cache.decodeAddress(addRunDataIndex+winHeader.sourceLength, instruction.mode);
					var absAddr = 0;

					// source segment and target segment are treated as if they're concatenated
					var sourceData = null;
					if(addr < winHeader.sourceLength){
						absAddr = winHeader.sourcePosition + addr;
						if(winHeader.indicator & VCD_SOURCE){
							sourceData = romFile;
						}else if(winHeader.indicator & VCD_TARGET){
							sourceData = tempFile;
						}
					}else{
						absAddr = targetWindowPosition + (addr - winHeader.sourceLength);
						sourceData = tempFile;
					}

                    while(size--){
						tempFile._u8array[targetWindowPosition + addRunDataIndex++]=sourceData._u8array[absAddr++];
                        //targetU8[targetWindowPosition + targetWindowOffs++] = copySourceU8[absAddr++];
                    }
					//to-do: test
					//sourceData.copyToFile2(tempFile, absAddr, size, targetWindowPosition + addRunDataIndex);
					//addRunDataIndex += size;
				}else if(instruction.type===VCD_RUN){
					var runByte = addRunDataStream.readU8();
					var offset = targetWindowPosition + addRunDataIndex;
					for(var j=0; j<size; j++){
						tempFile._u8array[offset+j]=runByte;
					}

					addRunDataIndex += size;
				}else{
					throw new Error('invalid instruction type found');
				}
			}
		}

		if(validate && winHeader.adler32 && (winHeader.adler32 !== adler32(tempFile, targetWindowPosition, winHeader.targetWindowLength))){
			throw new Error('Target ROM checksum mismatch');
		}

		parser.skip(winHeader.addRunDataLength + winHeader.addressesLength + winHeader.instructionsLength);
		targetWindowPosition += winHeader.targetWindowLength;
	}

	//console.log((performance.now()-t0)/1000);
	return tempFile;
}

VCDIFF.MAGIC=VCDIFF_MAGIC;

VCDIFF.fromFile=function(file){
	return new VCDIFF(file);
}







function VCDIFF_Parser(binFile, offset)
{
	this.fileSize=binFile.fileSize;
	this._u8array=binFile._u8array;
    this.offset=offset || 0;

	/* reimplement readU8, readU32 and skip from BinFile */
	/* in web implementation, there are no guarantees BinFile will be dynamically loaded before this one */
	/* so we cannot rely on cloning BinFile.prototype */
	this.readU8 = binFile.readU8;
	this.readU32 = binFile.readU32;
	this.skip = binFile.skip;
	this.isEOF = binFile.isEOF;
	this.seek = binFile.seek;
}
VCDIFF_Parser.prototype.read7BitEncodedInt=function(){
	var num=0, bits = 0;

	do {
		bits = this.readU8();
		num = (num << 7) + (bits & 0x7f); 
	} while(bits & 0x80);

	return num;
}
VCDIFF_Parser.prototype.decodeWindowHeader=function(){
	var windowHeader={
		indicator:this.readU8(),
		sourceLength:0,
		sourcePosition:0,
		adler32:false
	};


	if(windowHeader.indicator & (VCD_SOURCE | VCD_TARGET)){
		windowHeader.sourceLength = this.read7BitEncodedInt();
		windowHeader.sourcePosition = this.read7BitEncodedInt();
	}

	windowHeader.deltaLength = this.read7BitEncodedInt();
	windowHeader.targetWindowLength = this.read7BitEncodedInt();
	windowHeader.deltaIndicator = this.readU8(); // secondary compression: 1=VCD_DATACOMP,2=VCD_INSTCOMP,4=VCD_ADDRCOMP
	if(windowHeader.deltaIndicator!==0){
		throw new Error('unimplemented windowHeader.deltaIndicator:'+windowHeader.deltaIndicator);
	}
	
	windowHeader.addRunDataLength = this.read7BitEncodedInt();
	windowHeader.instructionsLength = this.read7BitEncodedInt();
	windowHeader.addressesLength = this.read7BitEncodedInt();

	if(windowHeader.indicator & VCD_ADLER32){
		windowHeader.adler32 = this.readU32();
	}


	return windowHeader;
}


VCDIFF_Parser.prototype.copyToFile2=function(target, targetOffset, len){
	for(var i=0; i<len; i++){
		target._u8array[targetOffset+i]=this._u8array[this.offset+i];
	}
	//this.file.copyToFile(target, this.offset, len, targetOffset);
	this.skip(len);
}

//------------------------------------------------------



// hdrIndicator
const VCD_DECOMPRESS = 0x01;
const VCD_CODETABLE  = 0x02;
const VCD_APPHEADER  = 0x04; // nonstandard?

// winIndicator
const VCD_SOURCE  = 0x01;
const VCD_TARGET  = 0x02;
const VCD_ADLER32 = 0x04;





function VCD_Instruction(instruction, size, mode){
	this.type=instruction;
	this.size=size;
	this.mode=mode;
}

/*
	build the default code table (used to encode/decode instructions) specified in RFC 3284
	heavily based on
	https://github.com/vic-alexiev/TelerikAcademy/blob/master/C%23%20Fundamentals%20II/Homework%20Assignments/3.%20Methods/000.%20MiscUtil/Compression/Vcdiff/CodeTable.cs
*/
const VCD_NOOP=0;
const VCD_ADD=1;
const VCD_RUN=2;
const VCD_COPY=3;
const VCD_DEFAULT_CODE_TABLE=(function(){
	var entries=[];

	var empty = {type: VCD_NOOP, size: 0, mode: 0};

	// 0
	entries.push([{type: VCD_RUN, size: 0, mode: 0}, empty]);

	// 1,18
	for(var size=0; size<18; size++){
		entries.push([{type: VCD_ADD, size: size, mode: 0}, empty]);
	}

	// 19,162
	for(var mode=0; mode<9; mode++){
		entries.push([{type: VCD_COPY, size: 0, mode: mode}, empty]);
		
		for(var size=4; size<19; size++){
			entries.push([{type: VCD_COPY, size: size, mode: mode}, empty]);
		}
	}

	// 163,234
	for(var mode=0; mode<6; mode++){
		for(var addSize=1; addSize<5; addSize++){
			for(var copySize=4; copySize<7; copySize++){
				entries.push([{type:  VCD_ADD, size: addSize,  mode: 0},
							{type: VCD_COPY, size: copySize, mode: mode}]);
			}
		}
	}

	// 235,246
	for(var mode=6; mode<9; mode++){
		for(var addSize=1; addSize<5; addSize++){
			entries.push([{type:  VCD_ADD, size: addSize, mode: 0},
						{type: VCD_COPY, size:       4, mode: mode}]);
		}
	}

	// 247,255
	for(var mode=0; mode<9; mode++){
		entries.push([{type: VCD_COPY, size: 4, mode: mode},
					{type:  VCD_ADD, size: 1, mode: 0}]); 
	}

	return entries;
})();



/*
	ported from https://github.com/vic-alexiev/TelerikAcademy/tree/master/C%23%20Fundamentals%20II/Homework%20Assignments/3.%20Methods/000.%20MiscUtil/Compression/Vcdiff
	by Victor Alexiev (https://github.com/vic-alexiev)
*/
const VCD_MODE_SELF=0;
const VCD_MODE_HERE=1;
function VCD_AdressCache(nearSize, sameSize){
	this.nearSize=nearSize;
	this.sameSize=sameSize;

	this.near=new Array(nearSize);
	this.same=new Array(sameSize*256);
}
VCD_AdressCache.prototype.reset=function(addressStream){
	this.nextNearSlot=0;
	this.near.fill(0);
	this.same.fill(0);

	this.addressStream=addressStream;
}
VCD_AdressCache.prototype.decodeAddress=function(here, mode){
	var address=0;

	if(mode===VCD_MODE_SELF){
		address=this.addressStream.read7BitEncodedInt();
	}else if(mode===VCD_MODE_HERE){
		address=here-this.addressStream.read7BitEncodedInt();
	}else if(mode-2<this.nearSize){ //near cache
		address=this.near[mode-2]+this.addressStream.read7BitEncodedInt();
	}else{ //same cache
		var m=mode-(2+this.nearSize);
		address=this.same[m*256+this.addressStream.readU8()];
	}
	
	this.update(address);
	return address;
}
VCD_AdressCache.prototype.update=function(address){
	if(this.nearSize>0){
		this.near[this.nextNearSlot]=address;
		this.nextNearSlot=(this.nextNearSlot+1)%this.nearSize;
	}

	if(this.sameSize>0){
		this.same[address%(this.sameSize*256)]=address;
	}
}
/* ---- RomPatcher.js ---- */
/*
* Rom Patcher JS core
* A ROM patcher/builder made in JavaScript, can be implemented as a webapp or a Node.JS CLI tool
* By Marc Robledo https://www.marcrobledo.com
* Sourcecode: https://github.com/marcrobledo/RomPatcher.js
* License:
*
* MIT License
* 
* Copyright (c) 2016-2025 Marc Robledo
* 
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
* 
* The above copyright notice and this permission notice shall be included in all
* copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
* SOFTWARE.
*/

const RomPatcher = (function () {
	const TOO_BIG_ROM_SIZE = 67108863;

	const HEADERS_INFO = [
		{ extensions: ['nes'], size: 16, romSizeMultiple: 1024, name: 'iNES' }, /* https://www.nesdev.org/wiki/INES */
		{ extensions: ['fds'], size: 16, romSizeMultiple: 65500, name: 'fwNES' }, /* https://www.nesdev.org/wiki/FDS_file_format */
		{ extensions: ['lnx'], size: 64, romSizeMultiple: 1024, name: 'LNX' },
		{ extensions: ['sfc', 'smc', 'swc', 'fig'], size: 512, romSizeMultiple: 262144, name: 'SNES copier' },
	];

	const GAME_BOY_NINTENDO_LOGO = [
		0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b, 0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
		0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e, 0xdc, 0xcc, 0x6e, 0xe6, 0xdd, 0xdd, 0xd9, 0x99
	];



	const _getRomSystem = function (binFile) {
		/* to-do: add more systems */
		const extension = binFile.getExtension().trim();
		if (binFile.fileSize > 0x0200 && binFile.fileSize % 4 === 0) {
			if ((extension === 'gb' || extension === 'gbc') && binFile.fileSize % 0x4000 === 0) {
				binFile.seek(0x0104);
				var valid = true;
				for (var i = 0; i < GAME_BOY_NINTENDO_LOGO.length && valid; i++) {
					if (GAME_BOY_NINTENDO_LOGO[i] !== binFile.readU8())
						valid = false;
				}
				if (valid)
					return 'gb';
			} else if (extension === 'md' || extension === 'bin') {
				binFile.seek(0x0100);
				if (/SEGA (GENESIS|MEGA DR)/.test(binFile.readString(12)))
					return 'smd';
			} else if (extension === 'z64' && binFile.fileSize >= 0x400000) {
				return 'n64'
			}
		} else if (extension === 'fds' && binFile.fileSize % 65500 === 0) {
			return 'fds'
		}
		return null;
	}
	const _getRomAdditionalChecksum = function (binFile) {
		/* to-do: add more systems */
		const romSystem = _getRomSystem(binFile);
		if (romSystem === 'n64') {
			binFile.seek(0x3c);
			const cartId = binFile.readString(3);

			binFile.seek(0x10);
			const crc = binFile.readBytes(8).reduce(function (hex, b) {
				if (b < 16)
					return hex + '0' + b.toString(16);
				else
					return hex + b.toString(16);
			}, '');
			return cartId + ' (' + crc + ')';
		}
		return null;
	}

	return {
		parsePatchFile: function (patchFile) {
			if (!(patchFile instanceof BinFile))
				throw new Error('Patch file is not an instance of BinFile');

			patchFile.littleEndian = false;
			patchFile.seek(0);

			var header = patchFile.readString(8);
			var patch = null;
			if (header.startsWith(IPS.MAGIC)) {
				patch = IPS.fromFile(patchFile);
			} else if (header.startsWith(UPS.MAGIC)) {
				patch = UPS.fromFile(patchFile);
			} else if (header.startsWith(APS.MAGIC)) {
				patch = APS.fromFile(patchFile);
			} else if (header.startsWith(APSGBA.MAGIC)) {
				patch = APSGBA.fromFile(patchFile);
			} else if (header.startsWith(BPS.MAGIC)) {
				patch = BPS.fromFile(patchFile);
			} else if (header.startsWith(RUP.MAGIC)) {
				patch = RUP.fromFile(patchFile);
			} else if (header.startsWith(PPF.MAGIC)) {
				patch = PPF.fromFile(patchFile);
			} else if (header.startsWith(BDF.MAGIC)) {
				patch = BDF.fromFile(patchFile);
			} else if (header.startsWith(PMSR.MAGIC)) {
				patch = PMSR.fromFile(patchFile);
			} else if (header.startsWith(VCDIFF.MAGIC)) {
				patch = VCDIFF.fromFile(patchFile);
			}

			if (patch)
				patch._originalPatchFile = patchFile;

			return patch;
		},

		validateRom: function (romFile, patch, skipHeaderSize) {
			if (!(romFile instanceof BinFile))
				throw new Error('ROM file is not an instance of BinFile');
			else if (typeof patch !== 'object')
				throw new Error('Unknown patch format');


			if (typeof skipHeaderSize !== 'number' || skipHeaderSize < 0)
				skipHeaderSize = 0;

			if (
				typeof patch.validateSource === 'function' && !patch.validateSource(romFile, skipHeaderSize)
			) {
				return false;
			}
			return true;
		},

		applyPatch: function (romFile, patch, optionsParam) {
			if (!(romFile instanceof BinFile))
				throw new Error('ROM file is not an instance of BinFile');
			else if (typeof patch !== 'object')
				throw new Error('Unknown patch format');


			const options = {
				requireValidation: false,
				removeHeader: false,
				addHeader: false,
				fixChecksum: false,
				outputSuffix: true
			};
			if (typeof optionsParam === 'object') {
				if (typeof optionsParam.requireValidation !== 'undefined')
					options.requireValidation = !!optionsParam.requireValidation;
				if (typeof optionsParam.removeHeader !== 'undefined')
					options.removeHeader = !!optionsParam.removeHeader;
				if (typeof optionsParam.addHeader !== 'undefined')
					options.addHeader = !!optionsParam.addHeader;
				if (typeof optionsParam.fixChecksum !== 'undefined')
					options.fixChecksum = !!optionsParam.fixChecksum;
				if (typeof optionsParam.outputSuffix !== 'undefined')
					options.outputSuffix = !!optionsParam.outputSuffix;
			}

			var extractedHeader = false;
			var fakeHeaderSize = 0;
			if (options.removeHeader) {
				const headerInfo = RomPatcher.isRomHeadered(romFile);
				if (headerInfo) {
					const splitData = RomPatcher.removeHeader(romFile);
					extractedHeader = splitData.header;
					romFile = splitData.rom;
				}
			} else if (options.addHeader) {
				const headerInfo = RomPatcher.canRomGetHeader(romFile);
				if (headerInfo) {
					fakeHeaderSize = headerInfo.fileSize;
					romFile = RomPatcher.addFakeHeader(romFile);
				}
			}

			if (options.requireValidation && !RomPatcher.validateRom(romFile, patch)) {
				throw new Error('Invalid input ROM checksum');
			}

			var patchedRom = patch.apply(romFile);
			if (extractedHeader) {
				/* reinsert header */
				if (options.fixChecksum)
					RomPatcher.fixRomHeaderChecksum(patchedRom);

				const patchedRomWithHeader = new BinFile(extractedHeader.fileSize + patchedRom.fileSize);
				patchedRomWithHeader.fileName = patchedRom.fileName;
				patchedRomWithHeader.fileType = patchedRom.fileType;
				extractedHeader.copyTo(patchedRomWithHeader, 0, extractedHeader.fileSize);
				patchedRom.copyTo(patchedRomWithHeader, 0, patchedRom.fileSize, extractedHeader.fileSize);

				patchedRom = patchedRomWithHeader;
			} else if (fakeHeaderSize) {
				/* remove fake header */
				const patchedRomWithoutFakeHeader = patchedRom.slice(fakeHeaderSize);

				if (options.fixChecksum)
					RomPatcher.fixRomHeaderChecksum(patchedRomWithoutFakeHeader);

				patchedRom = patchedRomWithoutFakeHeader;

			} else if (options.fixChecksum) {
				RomPatcher.fixRomHeaderChecksum(patchedRom);
			}

			if (options.outputSuffix) {
				patchedRom.fileName = romFile.fileName.replace(/\.([^\.]*?)$/, ' (patched).$1');
				if(patchedRom.unpatched)
					patchedRom.fileName = patchedRom.fileName.replace(' (patched)', ' (unpatched)');
			} else if (patch._originalPatchFile) {
				patchedRom.fileName = patch._originalPatchFile.fileName.replace(/\.\w+$/i, (/\.\w+$/i.test(romFile.fileName) ? romFile.fileName.match(/\.\w+$/i)[0] : ''));
			} else {
				patchedRom.fileName = romFile.fileName;
			}

			return patchedRom;
		},

		createPatch: function (originalFile, modifiedFile, format, metadata) {
			if (!(originalFile instanceof BinFile))
				throw new Error('Original ROM file is not an instance of BinFile');
			else if (!(modifiedFile instanceof BinFile))
				throw new Error('Modified ROM file is not an instance of BinFile');

			if (typeof format === 'string')
				format = format.trim().toLowerCase();
			else if (typeof format === 'undefined')
				format = 'ips';

			var patch;
			if (format === 'ips') {
				patch = IPS.buildFromRoms(originalFile, modifiedFile);
			} else if (format === 'bps') {
				patch = BPS.buildFromRoms(originalFile, modifiedFile, (originalFile.fileSize <= 4194304));
			} else if (format === 'ppf') {
				patch = PPF.buildFromRoms(originalFile, modifiedFile);
			} else if (format === 'ups') {
				patch = UPS.buildFromRoms(originalFile, modifiedFile);
			} else if (format === 'aps') {
				patch = APS.buildFromRoms(originalFile, modifiedFile);
			} else if (format === 'rup') {
				patch = RUP.buildFromRoms(originalFile, modifiedFile, metadata && metadata.Description? metadata.Description : null);
			}  else if (format === 'ebp') {
				patch = IPS.buildFromRoms(originalFile, modifiedFile, metadata);
			} else {
				throw new Error('Invalid patch format');
			}

			if (
				!(format === 'ppf' && originalFile.fileSize > modifiedFile.fileSize) && //skip verification if PPF and PPF+modified size>original size
				modifiedFile.hashCRC32() !== patch.apply(originalFile).hashCRC32()
			) {
				//throw new Error('Unexpected error: verification failed. Patched file and modified file mismatch. Please report this bug.');
			}
			return patch;
		},


		/* check if ROM can inject a fake header (for patches that require a headered ROM) */
		canRomGetHeader: function (romFile) {
			if (romFile.fileSize <= 0x600000) {
				const compatibleHeader = HEADERS_INFO.find(headerInfo => headerInfo.extensions.indexOf(romFile.getExtension()) !== -1 && romFile.fileSize % headerInfo.romSizeMultiple === 0);
				if (compatibleHeader) {
					return {
						name: compatibleHeader.name,
						size: compatibleHeader.size
					};
				}
			}
			return null;
		},

		/* check if ROM has a known header */
		isRomHeadered: function (romFile) {
			if (romFile.fileSize <= 0x600200 && romFile.fileSize % 1024 !== 0) {
				const compatibleHeader = HEADERS_INFO.find(headerInfo => headerInfo.extensions.indexOf(romFile.getExtension()) !== -1 && (romFile.fileSize - headerInfo.size) % headerInfo.romSizeMultiple === 0);
				if (compatibleHeader) {
					return {
						name: compatibleHeader.name,
						size: compatibleHeader.size
					};
				}
			}
			return null;
		},

		/* remove ROM header */
		removeHeader: function (romFile) {
			const headerInfo = RomPatcher.isRomHeadered(romFile);
			if (headerInfo) {
				return {
					header: romFile.slice(0, headerInfo.size),
					rom: romFile.slice(headerInfo.size)
				}
			}
			return null;
		},

		/* add fake ROM header */
		addFakeHeader: function (romFile) {
			const headerInfo = RomPatcher.canRomGetHeader(romFile);
			if (headerInfo) {
				const romWithFakeHeader = new BinFile(headerInfo.size + romFile.fileSize);
				romWithFakeHeader.fileName = romFile.fileName;
				romWithFakeHeader.fileType = romFile.fileType;
				romFile.copyTo(romWithFakeHeader, 0, romFile.fileSize, headerInfo.size);

				//add a correct FDS header
				if (_getRomSystem(romWithFakeHeader) === 'fds') {
					romWithFakeHeader.seek(0);
					romWithFakeHeader.writeBytes([0x46, 0x44, 0x53, 0x1a, romFile.fileSize / 65500]);
				}

				romWithFakeHeader.fakeHeader = true;

				return romWithFakeHeader;
			}
			return null;
		},

		/* get ROM internal checksum, if possible */
		fixRomHeaderChecksum: function (romFile) {
			const romSystem = _getRomSystem(romFile);

			if (romSystem === 'gb') {
				/* get current checksum */
				romFile.seek(0x014d);
				const currentChecksum = romFile.readU8();

				/* calculate checksum */
				var newChecksum = 0x00;
				romFile.seek(0x0134);
				for (var i = 0; i <= 0x18; i++) {
					newChecksum = ((newChecksum - romFile.readU8() - 1) >>> 0) & 0xff;
				}

				/* fix checksum */
				if (currentChecksum !== newChecksum) {
					console.log('fixed Game Boy checksum');
					romFile.seek(0x014d);
					romFile.writeU8(newChecksum);
					return true;
				}

			} else if (romSystem === 'smd') {
				/* get current checksum */
				romFile.seek(0x018e);
				const currentChecksum = romFile.readU16();

				/* calculate checksum */
				var newChecksum = 0x0000;
				romFile.seek(0x0200);
				while (!romFile.isEOF()) {
					newChecksum = ((newChecksum + romFile.readU16()) >>> 0) & 0xffff;
				}

				/* fix checksum */
				if (currentChecksum !== newChecksum) {
					console.log('fixed Megadrive/Genesis checksum');
					romFile.seek(0x018e);
					romFile.writeU16(newChecksum);
					return true;
				}
			}

			return false;
		},

		/* get ROM additional checksum info, if possible */
		getRomAdditionalChecksum: function (romFile) {
			return _getRomAdditionalChecksum(romFile);
		},


		/* check if ROM is too big */
		isRomTooBig: function (romFile) {
			return romFile && romFile.fileSize > TOO_BIG_ROM_SIZE;
		}
	}
}());


if (typeof module !== 'undefined' && module.exports) {
	module.exports = RomPatcher;

	IPS = require('./modules/RomPatcher.format.ips');
	UPS = require('./modules/RomPatcher.format.ups');
	APS = require('./modules/RomPatcher.format.aps_n64');
	APSGBA = require('./modules/RomPatcher.format.aps_gba');
	BPS = require('./modules/RomPatcher.format.bps');
	RUP = require('./modules/RomPatcher.format.rup');
	PPF = require('./modules/RomPatcher.format.ppf');
	BDF = require('./modules/RomPatcher.format.bdf');
	PMSR = require('./modules/RomPatcher.format.pmsr');
	VCDIFF = require('./modules/RomPatcher.format.vcdiff');
}